/*
 * Copyright 2018 ConsenSys AG.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package tech.pegasys.pantheon.tests.acceptance.dsl.node;

import tech.pegasys.pantheon.tests.acceptance.dsl.condition.Condition;
import tech.pegasys.pantheon.tests.acceptance.dsl.httptransaction.HttpTransaction;
import tech.pegasys.pantheon.tests.acceptance.dsl.transaction.Transaction;
import tech.pegasys.pantheon.tests.acceptance.dsl.waitcondition.WaitCondition;

public interface Node {
  <T> T execute(Transaction<T> transaction);

  <T> T executeHttpTransaction(HttpTransaction<T> transaction);

  void verify(final Condition expected);

  void waitUntil(final WaitCondition condition);
}
