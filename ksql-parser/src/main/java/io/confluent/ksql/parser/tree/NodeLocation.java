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

package io.confluent.ksql.parser.tree;

import javax.annotation.concurrent.Immutable;

@Immutable
public final class NodeLocation {

  private final int line;
  private final int charPositionInLine;

  public NodeLocation(final int line, final int charPositionInLine) {
    this.line = line;
    this.charPositionInLine = charPositionInLine;
  }

  public int getLineNumber() {
    return line;
  }

  public int getColumnNumber() {
    return charPositionInLine + 1;
  }

  @Override
  public String toString() {
    return String.format("Line: %d, Col: %d", line, charPositionInLine + 1);
  }
}
