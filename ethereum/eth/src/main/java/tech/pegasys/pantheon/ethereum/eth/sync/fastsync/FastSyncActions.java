/*
 * Copyright 2019 ConsenSys AG.
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
package tech.pegasys.pantheon.ethereum.eth.sync.fastsync;

import static java.util.concurrent.CompletableFuture.completedFuture;
import static tech.pegasys.pantheon.ethereum.eth.sync.fastsync.FastSyncError.CHAIN_TOO_SHORT;

import tech.pegasys.pantheon.ethereum.ProtocolContext;
import tech.pegasys.pantheon.ethereum.core.BlockHeader;
import tech.pegasys.pantheon.ethereum.eth.manager.EthContext;
import tech.pegasys.pantheon.ethereum.eth.manager.EthScheduler;
import tech.pegasys.pantheon.ethereum.eth.sync.SynchronizerConfiguration;
import tech.pegasys.pantheon.ethereum.eth.sync.state.SyncState;
import tech.pegasys.pantheon.ethereum.eth.sync.tasks.WaitForPeersTask;
import tech.pegasys.pantheon.ethereum.mainnet.ProtocolSchedule;
import tech.pegasys.pantheon.ethereum.mainnet.ScheduleBasedBlockHashFunction;
import tech.pegasys.pantheon.metrics.Counter;
import tech.pegasys.pantheon.metrics.LabelledMetric;
import tech.pegasys.pantheon.metrics.OperationTimer;
import tech.pegasys.pantheon.util.ExceptionUtils;

import java.time.Duration;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class FastSyncActions<C> {

  private static final Logger LOG = LogManager.getLogger();
  private final SynchronizerConfiguration syncConfig;
  private final ProtocolSchedule<C> protocolSchedule;
  private final ProtocolContext<C> protocolContext;
  private final EthContext ethContext;
  private final SyncState syncState;
  private final PivotHeaderStorage pivotHeaderStorage;
  private final LabelledMetric<OperationTimer> ethTasksTimer;
  private final LabelledMetric<Counter> fastSyncValidationCounter;

  public FastSyncActions(
      final SynchronizerConfiguration syncConfig,
      final ProtocolSchedule<C> protocolSchedule,
      final ProtocolContext<C> protocolContext,
      final EthContext ethContext,
      final SyncState syncState,
      final PivotHeaderStorage pivotHeaderStorage,
      final LabelledMetric<OperationTimer> ethTasksTimer,
      final LabelledMetric<Counter> fastSyncValidationCounter) {
    this.syncConfig = syncConfig;
    this.protocolSchedule = protocolSchedule;
    this.protocolContext = protocolContext;
    this.ethContext = ethContext;
    this.syncState = syncState;
    this.pivotHeaderStorage = pivotHeaderStorage;
    this.ethTasksTimer = ethTasksTimer;
    this.fastSyncValidationCounter = fastSyncValidationCounter;
  }

  public CompletableFuture<Void> waitForSuitablePeers() {
    final WaitForPeersTask waitForPeersTask =
        WaitForPeersTask.create(
            ethContext, syncConfig.getFastSyncMinimumPeerCount(), ethTasksTimer);

    final EthScheduler scheduler = ethContext.getScheduler();
    final CompletableFuture<Void> result = new CompletableFuture<>();
    scheduler
        .timeout(waitForPeersTask, syncConfig.getFastSyncMaximumPeerWaitTime())
        .handle(
            (waitResult, error) -> {
              if (ExceptionUtils.rootCause(error) instanceof TimeoutException) {
                if (ethContext.getEthPeers().availablePeerCount() > 0) {
                  LOG.warn(
                      "Fast sync timed out before minimum peer count was reached. Continuing with reduced peers.");
                  result.complete(null);
                } else {
                  LOG.warn(
                      "Maximum wait time for fast sync reached but no peers available. Continuing to wait for any available peer.");
                  waitForAnyPeer()
                      .thenAccept(result::complete)
                      .exceptionally(
                          taskError -> {
                            result.completeExceptionally(error);
                            return null;
                          });
                }
              } else if (error != null) {
                LOG.error("Failed to find peers for fast sync", error);
                result.completeExceptionally(error);
              } else {
                result.complete(null);
              }
              return null;
            });

    return result;
  }

  private CompletableFuture<Void> waitForAnyPeer() {
    final CompletableFuture<Void> result = new CompletableFuture<>();
    waitForAnyPeer(result);
    return result;
  }

  private void waitForAnyPeer(final CompletableFuture<Void> result) {
    ethContext
        .getScheduler()
        .timeout(WaitForPeersTask.create(ethContext, 1, ethTasksTimer))
        .whenComplete(
            (waitResult, throwable) -> {
              if (ExceptionUtils.rootCause(throwable) instanceof TimeoutException) {
                waitForAnyPeer(result);
              } else if (throwable != null) {
                result.completeExceptionally(throwable);
              } else {
                result.complete(waitResult);
              }
            });
  }

  public CompletableFuture<FastSyncState> selectPivotBlock() {
    return loadPivotBlockFromStorage().orElseGet(this::selectPivotBlockFromPeers);
  }

  private Optional<CompletableFuture<FastSyncState>> loadPivotBlockFromStorage() {
    return pivotHeaderStorage
        .loadPivotBlockHeader(ScheduleBasedBlockHashFunction.create(protocolSchedule))
        .map(
            header ->
                completedFuture(
                    new FastSyncState(OptionalLong.of(header.getNumber()), Optional.of(header))));
  }

  private CompletableFuture<FastSyncState> selectPivotBlockFromPeers() {
    return ethContext
        .getEthPeers()
        .bestPeer()
        .filter(peer -> peer.chainState().hasEstimatedHeight())
        .map(
            peer -> {
              final long pivotBlockNumber =
                  peer.chainState().getEstimatedHeight() - syncConfig.fastSyncPivotDistance();
              if (pivotBlockNumber <= BlockHeader.GENESIS_BLOCK_NUMBER) {
                throw new FastSyncException(CHAIN_TOO_SHORT);
              } else {
                LOG.info("Selecting block number {} as fast sync pivot block.", pivotBlockNumber);
                return completedFuture(new FastSyncState(OptionalLong.of(pivotBlockNumber)));
              }
            })
        .orElseGet(this::retrySelectPivotBlockAfterDelay);
  }

  private CompletableFuture<FastSyncState> retrySelectPivotBlockAfterDelay() {
    LOG.info("Waiting for peer with known chain height");
    return ethContext
        .getScheduler()
        .scheduleFutureTask(
            () -> waitForAnyPeer().thenCompose(ignore -> selectPivotBlock()),
            Duration.ofSeconds(1));
  }

  public CompletableFuture<FastSyncState> downloadPivotBlockHeader(
      final FastSyncState currentState) {
    if (currentState.getPivotBlockHeader().isPresent()) {
      return completedFuture(currentState);
    }
    return new PivotBlockRetriever<>(
            protocolSchedule,
            ethContext,
            ethTasksTimer,
            currentState.getPivotBlockNumber().getAsLong())
        .downloadPivotBlockHeader()
        .thenApply(this::storePivotBlockHeader);
  }

  private FastSyncState storePivotBlockHeader(final FastSyncState fastSyncState) {
    pivotHeaderStorage.storePivotBlockHeader(fastSyncState.getPivotBlockHeader().get());
    return fastSyncState;
  }

  public CompletableFuture<Void> downloadChain(final FastSyncState currentState) {
    final FastSyncChainDownloader<C> downloader =
        new FastSyncChainDownloader<>(
            syncConfig,
            protocolSchedule,
            protocolContext,
            ethContext,
            syncState,
            ethTasksTimer,
            fastSyncValidationCounter,
            currentState.getPivotBlockHeader().get());
    return downloader.start();
  }
}
