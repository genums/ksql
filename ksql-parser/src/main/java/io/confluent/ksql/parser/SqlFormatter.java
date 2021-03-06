/*
 * Copyright 2018 Confluent Inc.
 *
 * Licensed under the Confluent Community License (the "License"); you may not use
 * this file except in compliance with the License.  You may obtain a copy of the
 * License at
 *
 * http://www.confluent.io/confluent-community-license
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OF ANY KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations under the License.
 */

package io.confluent.ksql.parser;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.Iterables.getOnlyElement;
import static com.google.common.collect.Iterables.transform;

import com.google.common.base.Joiner;
import com.google.common.base.Strings;
import io.confluent.ksql.parser.tree.AliasedRelation;
import io.confluent.ksql.parser.tree.AllColumns;
import io.confluent.ksql.parser.tree.AstVisitor;
import io.confluent.ksql.parser.tree.CreateStream;
import io.confluent.ksql.parser.tree.CreateStreamAsSelect;
import io.confluent.ksql.parser.tree.CreateTable;
import io.confluent.ksql.parser.tree.CreateTableAsSelect;
import io.confluent.ksql.parser.tree.DropTable;
import io.confluent.ksql.parser.tree.Explain;
import io.confluent.ksql.parser.tree.Expression;
import io.confluent.ksql.parser.tree.InsertInto;
import io.confluent.ksql.parser.tree.Join;
import io.confluent.ksql.parser.tree.JoinCriteria;
import io.confluent.ksql.parser.tree.JoinOn;
import io.confluent.ksql.parser.tree.ListFunctions;
import io.confluent.ksql.parser.tree.Node;
import io.confluent.ksql.parser.tree.Query;
import io.confluent.ksql.parser.tree.Relation;
import io.confluent.ksql.parser.tree.Select;
import io.confluent.ksql.parser.tree.SelectItem;
import io.confluent.ksql.parser.tree.ShowColumns;
import io.confluent.ksql.parser.tree.SingleColumn;
import io.confluent.ksql.parser.tree.Table;
import io.confluent.ksql.parser.tree.TableElement;
import io.confluent.ksql.util.KsqlException;
import io.confluent.ksql.util.ParserUtil;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.apache.commons.lang3.StringUtils;

public final class SqlFormatter {

  private static final String INDENT = "   ";
  private static final Pattern NAME_PATTERN = Pattern.compile("[a-z_][a-z0-9_]*");

  private SqlFormatter() {
  }

  public static String formatSql(final Node root) {
    return formatSql(root, true);
  }

  public static String formatSql(final Node root, final boolean unmangleNames) {
    final StringBuilder builder = new StringBuilder();
    new Formatter(builder, unmangleNames).process(root, 0);
    return StringUtils.stripEnd(builder.toString(), "\n");
  }

  private static final class Formatter
          extends AstVisitor<Void, Integer> {

    private final StringBuilder builder;
    private final boolean unmangledNames;

    private Formatter(final StringBuilder builder, final boolean unmangleNames) {
      this.builder = builder;
      this.unmangledNames = unmangleNames;
    }

    @Override
    protected Void visitNode(final Node node, final Integer indent) {
      throw new UnsupportedOperationException("not yet implemented: " + node);
    }

    @Override
    protected Void visitExpression(final Expression node, final Integer indent) {
      checkArgument(indent == 0,
              "visitExpression should only be called at root");
      builder.append(ExpressionFormatter.formatExpression(node, unmangledNames));
      return null;
    }

    @Override
    protected Void visitQuery(final Query node, final Integer indent) {

      process(node.getSelect(), indent);

      append(indent, "FROM ");
      processRelation(node.getFrom(), indent);
      builder.append('\n');

      if (node.getWhere().isPresent()) {
        append(indent, "WHERE " + ExpressionFormatter.formatExpression(node.getWhere().get()))
            .append('\n');
      }

      if (node.getGroupBy().isPresent()) {
        append(indent, "GROUP BY "
            + (node.getGroupBy().get().isDistinct() ? " DISTINCT " : "")
            + ExpressionFormatter
            .formatGroupBy(node.getGroupBy().get().getGroupingElements()))
            .append('\n');
      }

      if (node.getHaving().isPresent()) {
        append(indent, "HAVING "
            + ExpressionFormatter.formatExpression(node.getHaving().get()))
            .append('\n');
      }

      if (node.getLimit().isPresent()) {
        append(indent, "LIMIT " + node.getLimit().getAsInt())
                .append('\n');
      }

      return null;
    }

    @Override
    protected Void visitSelect(final Select node, final Integer indent) {
      append(indent, "SELECT");
      if (node.isDistinct()) {
        builder.append(" DISTINCT");
      }

      final List<SelectItem> selectItems = node.getSelectItems()
          .stream()
          .map(item ->
              (item instanceof SingleColumn)
                  ? ((SingleColumn) item).getAllColumns().map(SelectItem.class::cast).orElse(item)
                  : item)
          .distinct()
          .collect(Collectors.toList());

      if (selectItems.size() > 1) {
        boolean first = true;
        for (final SelectItem item : selectItems) {
          builder.append("\n")
                  .append(indentString(indent))
                  .append(first ? "  " : ", ");

          process(item, indent);
          first = false;
        }
      } else {
        builder.append(' ');
        process(getOnlyElement(selectItems), indent);
      }

      builder.append('\n');

      return null;
    }

    @Override
    protected Void visitSingleColumn(final SingleColumn node, final Integer indent) {
      builder.append(ExpressionFormatter.formatExpression(node.getExpression()));
      if (node.getAlias().isPresent()) {
        builder.append(' ')
                .append('"')
                .append(node.getAlias().get())
                .append('"'); // TODO: handle quoting properly
      }

      return null;
    }

    @Override
    protected Void visitAllColumns(final AllColumns node, final Integer context) {
      builder.append(node.toString());

      return null;
    }

    @Override
    protected Void visitTable(final Table node, final Integer indent) {
      builder.append(node.getName().toString());
      return null;
    }

    @Override
    protected Void visitJoin(final Join node, final Integer indent) {
      final String type = node.getFormattedType();
      process(node.getLeft(), indent);

      builder.append('\n');
      append(indent, type).append(" JOIN ");

      process(node.getRight(), indent);

      final JoinCriteria criteria = node.getCriteria().orElseThrow(() ->
          new KsqlException("Join criteria is missing")
      );

      node.getWithinExpression().map((e) -> builder.append(e.toString()));
      final JoinOn on = (JoinOn) criteria;
      builder.append(" ON (")
          .append(ExpressionFormatter.formatExpression(on.getExpression()))
          .append(")");

      return null;
    }

    @Override
    protected Void visitAliasedRelation(final AliasedRelation node, final Integer indent) {
      process(node.getRelation(), indent);

      builder.append(' ')
              .append(node.getAlias());

      return null;
    }

    @Override
    protected Void visitCreateStream(final CreateStream node, final Integer indent) {
      builder.append("CREATE STREAM ");
      if (node.isNotExists()) {
        builder.append("IF NOT EXISTS ");
      }
      builder.append(node.getName())
          .append(" ");
      if (!node.getElements().isEmpty()) {
        builder.append("(");
        boolean addComma = false;
        for (final TableElement tableElement : node.getElements()) {
          if (addComma) {
            builder.append(", ");
          } else {
            addComma = true;
          }
          builder.append(ParserUtil.escapeIfLiteral(tableElement.getName()))
              .append(" ")
              .append(tableElement.getType());
        }
        builder.append(")");
      }
      if (!node.getProperties().isEmpty()) {
        builder.append(" WITH (");
        boolean addComma = false;
        for (final Map.Entry property: node.getProperties().entrySet()) {
          if (addComma) {
            builder.append(", ");
          } else {
            addComma = true;
          }
          builder.append(property.getKey().toString()).append("=").append(property.getValue()
                                                                              .toString());
        }
        builder.append(");");
      }
      return null;
    }

    @Override
    protected Void visitCreateTable(final CreateTable node, final Integer indent) {
      builder.append("CREATE TABLE ");
      if (node.isNotExists()) {
        builder.append("IF NOT EXISTS ");
      }
      builder.append(node.getName())
          .append(" ");
      if (!node.getElements().isEmpty()) {
        builder.append("(");
        boolean addComma = false;
        for (final TableElement tableElement: node.getElements()) {
          if (addComma) {
            builder.append(", ");
          } else {
            addComma = true;
          }
          builder.append(ParserUtil.escapeIfLiteral(tableElement.getName()))
              .append(" ")
              .append(tableElement.getType());
        }
        builder.append(")").append(" WITH (");
        addComma = false;
        for (final Map.Entry property: node.getProperties().entrySet()) {
          if (addComma) {
            builder.append(", ");
          } else {
            addComma = true;
          }
          builder.append(property.getKey().toString()).append("=").append(property.getValue()
                                                                              .toString());
        }
        builder.append(");");
      }
      return null;
    }

    @Override
    protected Void visitExplain(final Explain node, final Integer indent) {
      builder.append("EXPLAIN ");
      if (node.isAnalyze()) {
        builder.append("ANALYZE ");
      }

      builder.append("\n");

      process(node.getStatement(), indent);

      return null;
    }

    @Override
    protected Void visitShowColumns(final ShowColumns node, final Integer context) {
      builder.append("SHOW COLUMNS FROM ")
              .append(node.getTable());

      return null;
    }

    @Override
    protected Void visitShowFunctions(final ListFunctions node, final Integer context) {
      builder.append("SHOW FUNCTIONS");

      return null;
    }

    @Override
    protected Void visitCreateStreamAsSelect(final CreateStreamAsSelect node,
                                             final Integer indent) {
      builder.append("CREATE STREAM ");
      if (node.isNotExists()) {
        builder.append("IF NOT EXISTS ");
      }
      builder.append(node.getName());

      if (!node.getProperties().isEmpty()) {
        builder.append(" WITH (");
        Joiner.on(", ")
            .appendTo(builder, transform(
                node.getProperties().entrySet(), entry -> entry.getKey() + " = "
                                                          + ExpressionFormatter
                                                              .formatExpression(entry.getValue())));
        builder.append(")");
      }

      builder.append(" AS ");
      process(node.getQuery(), indent);
      processPartitionBy(node.getPartitionByColumn(), indent);
      return null;
    }

    @Override
    protected Void visitCreateTableAsSelect(final CreateTableAsSelect node, final Integer indent) {
      builder.append("CREATE TABLE ");
      if (node.isNotExists()) {
        builder.append("IF NOT EXISTS ");
      }
      builder.append(node.getName());

      if (!node.getProperties().isEmpty()) {
        builder.append(" WITH (");
        Joiner.on(", ")
                .appendTo(builder, transform(
                        node.getProperties().entrySet(), entry -> entry.getKey() + " = "
                                + ExpressionFormatter
                                .formatExpression(entry.getValue())));
        builder.append(")");
      }

      builder.append(" AS ");
      process(node.getQuery(), indent);
      return null;
    }



    private static String formatName(final String name) {
      if (NAME_PATTERN.matcher(name).matches()) {
        return name;
      }
      return "\"" + name + "\"";
    }

    @Override
    protected Void visitInsertInto(final InsertInto node, final Integer indent) {
      builder.append("INSERT INTO ");
      builder.append(node.getTarget());
      builder.append(" ");
      process(node.getQuery(), indent);
      processPartitionBy(node.getPartitionByColumn(), indent);
      return null;
    }

    @Override
    protected Void visitDropTable(final DropTable node, final Integer context) {
      builder.append("DROP TABLE ");
      if (node.getIfExists()) {
        builder.append("IF EXISTS ");
      }
      builder.append(node.getName());

      return null;
    }

    private void processRelation(final Relation relation, final Integer indent) {
      if (relation instanceof Table) {
        builder.append("TABLE ")
                .append(((Table) relation).getName())
                .append('\n');
      } else {
        process(relation, indent);
      }
    }

    private void processPartitionBy(
        final Optional<Expression> partitionByColumn,
        final Integer indent
    ) {
      partitionByColumn.ifPresent(partitionBy -> {
        append(indent, "PARTITION BY " + ExpressionFormatter.formatExpression(partitionBy))
            .append('\n');
      });
    }

    private StringBuilder append(final int indent, final String value) {
      return builder.append(indentString(indent))
              .append(value);
    }

    private static String indentString(final int indent) {
      return Strings.repeat(INDENT, indent);
    }
  }
}
