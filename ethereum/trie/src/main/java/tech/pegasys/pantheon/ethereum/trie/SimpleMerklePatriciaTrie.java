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
package tech.pegasys.pantheon.ethereum.trie;

import static com.google.common.base.Preconditions.checkNotNull;
import static tech.pegasys.pantheon.ethereum.trie.CompactEncoding.bytesToPath;

import tech.pegasys.pantheon.util.bytes.Bytes32;
import tech.pegasys.pantheon.util.bytes.BytesValue;

import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * An in-memory {@link MerklePatriciaTrie}.
 *
 * @param <V> The type of values stored by this trie.
 */
public class SimpleMerklePatriciaTrie<K extends BytesValue, V> implements MerklePatriciaTrie<K, V> {
  private final PathNodeVisitor<V> getVisitor = new GetVisitor<>();
  private final PathNodeVisitor<V> removeVisitor = new RemoveVisitor<>();
  private final DefaultNodeFactory<V> nodeFactory;

  private Node<V> root;

  /**
   * Create a trie.
   *
   * @param valueSerializer A function for serializing values to bytes.
   */
  public SimpleMerklePatriciaTrie(final Function<V, BytesValue> valueSerializer) {
    this.nodeFactory = new DefaultNodeFactory<>(valueSerializer);
    this.root = NullNode.instance();
  }

  @Override
  public Optional<V> get(final K key) {
    checkNotNull(key);
    return root.accept(getVisitor, bytesToPath(key)).getValue();
  }

  @Override
  public void put(final K key, final V value) {
    checkNotNull(key);
    checkNotNull(value);
    this.root = root.accept(new PutVisitor<>(nodeFactory, value), bytesToPath(key));
  }

  @Override
  public void remove(final K key) {
    checkNotNull(key);
    this.root = root.accept(removeVisitor, bytesToPath(key));
  }

  @Override
  public Bytes32 getRootHash() {
    return root.getHash();
  }

  @Override
  public String toString() {
    return getClass().getSimpleName() + "[" + getRootHash() + "]";
  }

  @Override
  public void commit(final NodeUpdater nodeUpdater) {
    // Nothing to do here
  }

  @Override
  public Map<Bytes32, V> entriesFrom(final Bytes32 startKeyHash, final int limit) {
    return StorageEntriesCollector.collectEntries(root, startKeyHash, limit);
  }
}
