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
package tech.pegasys.pantheon.consensus.ibft.messagewrappers;

import tech.pegasys.pantheon.consensus.ibft.IbftBlockHashing;
import tech.pegasys.pantheon.consensus.ibft.payload.ProposalPayload;
import tech.pegasys.pantheon.consensus.ibft.payload.SignedData;
import tech.pegasys.pantheon.ethereum.core.Block;
import tech.pegasys.pantheon.ethereum.core.Hash;
import tech.pegasys.pantheon.ethereum.rlp.BytesValueRLPOutput;
import tech.pegasys.pantheon.ethereum.rlp.RLP;
import tech.pegasys.pantheon.ethereum.rlp.RLPInput;
import tech.pegasys.pantheon.util.bytes.BytesValue;

public class Proposal extends IbftMessage<ProposalPayload> {

  private final Block proposedBlock;

  public Proposal(final SignedData<ProposalPayload> payload, final Block proposedBlock) {
    super(payload);
    this.proposedBlock = proposedBlock;
  }

  public Block getBlock() {
    return proposedBlock;
  }

  public Hash getDigest() {
    return getPayload().getDigest();
  }

  @Override
  public BytesValue encode() {
    final BytesValueRLPOutput rlpOut = new BytesValueRLPOutput();
    rlpOut.startList();
    getSignedPayload().writeTo(rlpOut);
    proposedBlock.writeTo(rlpOut);
    rlpOut.endList();
    return rlpOut.encoded();
  }

  public static Proposal decode(final BytesValue data) {
    RLPInput rlpIn = RLP.input(data);
    rlpIn.enterList();
    final SignedData<ProposalPayload> payload = SignedData.readSignedProposalPayloadFrom(rlpIn);
    final Block proposedBlock =
        Block.readFrom(rlpIn, IbftBlockHashing::calculateDataHashForCommittedSeal);
    rlpIn.leaveList();
    return new Proposal(payload, proposedBlock);
  }
}
