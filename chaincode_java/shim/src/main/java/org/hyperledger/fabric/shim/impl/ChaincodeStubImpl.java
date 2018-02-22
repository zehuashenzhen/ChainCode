/*
Copyright IBM Corp., DTCC All Rights Reserved.

SPDX-License-Identifier: Apache-2.0
*/

package org.hyperledger.fabric.shim.impl;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Timestamp;
import org.hyperledger.fabric.protos.common.Common;
import org.hyperledger.fabric.protos.common.Common.ChannelHeader;
import org.hyperledger.fabric.protos.common.Common.Header;
import org.hyperledger.fabric.protos.common.Common.HeaderType;
import org.hyperledger.fabric.protos.common.Common.SignatureHeader;
import org.hyperledger.fabric.protos.ledger.queryresult.KvQueryResult;
import org.hyperledger.fabric.protos.ledger.queryresult.KvQueryResult.KV;
import org.hyperledger.fabric.protos.peer.ChaincodeEventPackage.ChaincodeEvent;
import org.hyperledger.fabric.protos.peer.ChaincodeShim.QueryResultBytes;
import org.hyperledger.fabric.protos.peer.ProposalPackage.ChaincodeProposalPayload;
import org.hyperledger.fabric.protos.peer.ProposalPackage.Proposal;
import org.hyperledger.fabric.protos.peer.ProposalPackage.SignedProposal;
import org.hyperledger.fabric.shim.Chaincode.Response;
import org.hyperledger.fabric.shim.ChaincodeStub;
import org.hyperledger.fabric.shim.ledger.CompositeKey;
import org.hyperledger.fabric.shim.ledger.KeyModification;
import org.hyperledger.fabric.shim.ledger.KeyValue;
import org.hyperledger.fabric.shim.ledger.QueryResultsIterator;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static java.util.stream.Collectors.toList;

class ChaincodeStubImpl implements ChaincodeStub {

	private static final String UNSPECIFIED_KEY = new String(Character.toChars(0x000001));
	private final String channelId;
	private final String txId;
	private final Handler handler;
	private final List<ByteString> args;
	private final SignedProposal signedProposal;
	private final Instant txTimestamp;
	private final ByteString creator;
	private final Map<String, ByteString> transientMap;
	private final byte[] binding;
	private ChaincodeEvent event;

	ChaincodeStubImpl(String channelId, String txId, Handler handler, List<ByteString> args, SignedProposal signedProposal) {
		this.channelId = channelId;
		this.txId = txId;
		this.handler = handler;
		this.args = Collections.unmodifiableList(args);
		this.signedProposal = signedProposal;
		if(this.signedProposal == null) {
			this.creator = null;
			this.txTimestamp = null;
			this.transientMap = Collections.emptyMap();
			this.binding = null;
		} else {
			try {
				final Proposal proposal = Proposal.parseFrom(signedProposal.getProposalBytes());
				final Header header = Header.parseFrom(proposal.getHeader());
				final ChannelHeader channelHeader = ChannelHeader.parseFrom(header.getChannelHeader());
				validateProposalType(channelHeader);
				final SignatureHeader signatureHeader = SignatureHeader.parseFrom(header.getSignatureHeader());
				final ChaincodeProposalPayload chaincodeProposalPayload = ChaincodeProposalPayload.parseFrom(proposal.getPayload());
				final Timestamp timestamp = channelHeader.getTimestamp();

				this.txTimestamp = Instant.ofEpochSecond(timestamp.getSeconds(), timestamp.getNanos());
				this.creator = signatureHeader.getCreator();
				this.transientMap = chaincodeProposalPayload.getTransientMapMap();
				this.binding = computeBinding(channelHeader, signatureHeader);
			} catch (InvalidProtocolBufferException | NoSuchAlgorithmException e) {
				throw new RuntimeException(e);
			}
		}
	}

	private byte[] computeBinding(final ChannelHeader channelHeader, final SignatureHeader signatureHeader) throws NoSuchAlgorithmException {
		final MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");
		messageDigest.update(signatureHeader.getNonce().asReadOnlyByteBuffer());
		messageDigest.update(this.creator.asReadOnlyByteBuffer());
		final ByteBuffer epochBytes = ByteBuffer.allocate(Long.BYTES)
				.order(ByteOrder.LITTLE_ENDIAN)
				.putLong(channelHeader.getEpoch());
		epochBytes.flip();
		messageDigest.update(epochBytes);
		return messageDigest.digest();
	}

	private void validateProposalType(ChannelHeader channelHeader) {
		switch (Common.HeaderType.forNumber(channelHeader.getType())) {
		case ENDORSER_TRANSACTION:
		case CONFIG:
			return;
		default:
			throw new RuntimeException(String.format("Unexpected transaction type: %s", HeaderType.forNumber(channelHeader.getType())));
		}
	}

	@Override
	public List<byte[]> getArgs() {
		return args.stream().map(x -> x.toByteArray()).collect(Collectors.toList());
	}

	@Override
	public List<String> getStringArgs() {
		return args.stream().map(x -> x.toStringUtf8()).collect(Collectors.toList());
	}

	@Override
	public String getFunction() {
		return getStringArgs().size() > 0 ? getStringArgs().get(0) : null;
	}

	@Override
	public List<String> getParameters() {
		return getStringArgs().stream().skip(1).collect(toList());
	}

	@Override
	public void setEvent(String name, byte[] payload) {
		if (name == null || name.trim().length() == 0) throw new IllegalArgumentException("Event name cannot be null or empty string.");
		if (payload != null) {
			this.event = ChaincodeEvent.newBuilder()
					.setEventName(name)
					.setPayload(ByteString.copyFrom(payload))
					.build();
		} else {
			this.event = ChaincodeEvent.newBuilder()
					.setEventName(name)
					.build();
		}
	}

	@Override
	public ChaincodeEvent getEvent() {
		return event;
	}

	@Override
	public String getChannelId() {
		return channelId;
	}

	@Override
	public String getTxId() {
		return txId;
	}

	@Override
	public byte[] getState(String key) {
		return handler.getState(channelId, txId, key).toByteArray();
	}

	@Override
	public void putState(String key, byte[] value) {
		if(key == null) throw new NullPointerException("key cannot be null");
		if(key.length() == 0) throw new IllegalArgumentException("key cannot not be an empty string");
		handler.putState(channelId, txId, key, ByteString.copyFrom(value));
	}

	@Override
	public void delState(String key) {
		handler.deleteState(channelId, txId, key);
	}

	@Override
	public QueryResultsIterator<KeyValue> getStateByRange(String startKey, String endKey) {
		if (startKey == null || startKey.isEmpty()) startKey = UNSPECIFIED_KEY;
		if (endKey == null || endKey.isEmpty()) endKey = UNSPECIFIED_KEY;
		CompositeKey.validateSimpleKeys(startKey, endKey);

		return new QueryResultsIteratorImpl<KeyValue>(this.handler, getChannelId(), getTxId(),
				handler.getStateByRange(getChannelId(), getTxId(), startKey, endKey),
				queryResultBytesToKv.andThen(KeyValueImpl::new)
				);
	}

	private Function<QueryResultBytes, KV> queryResultBytesToKv = new Function<QueryResultBytes, KV>() {
		public KV apply(QueryResultBytes queryResultBytes) {
			try {
				return KV.parseFrom(queryResultBytes.getResultBytes());
			} catch (InvalidProtocolBufferException e) {
				throw new RuntimeException(e);
			}
		};
	};

	@Override
	public QueryResultsIterator<KeyValue> getStateByPartialCompositeKey(String compositeKey) {
		if (compositeKey == null || compositeKey.isEmpty()) {
			compositeKey = UNSPECIFIED_KEY;
		}
		return getStateByRange(compositeKey, compositeKey + "\udbff\udfff");
	}

	@Override
	public CompositeKey createCompositeKey(String objectType, String... attributes) {
		return new CompositeKey(objectType, attributes);
	}

	@Override
	public CompositeKey splitCompositeKey(String compositeKey) {
		return CompositeKey.parseCompositeKey(compositeKey);
	}

	@Override
	public QueryResultsIterator<KeyValue> getQueryResult(String query) {
		return new QueryResultsIteratorImpl<KeyValue>(this.handler, getChannelId(), getTxId(),
				handler.getQueryResult(getChannelId(), getTxId(), query),
				queryResultBytesToKv.andThen(KeyValueImpl::new)
				);
	}

	@Override
	public QueryResultsIterator<KeyModification> getHistoryForKey(String key) {
		return new QueryResultsIteratorImpl<KeyModification>(this.handler, getChannelId(), getTxId(),
				handler.getHistoryForKey(getChannelId(), getTxId(), key),
				queryResultBytesToKeyModification.andThen(KeyModificationImpl::new)
				);
	}

	private Function<QueryResultBytes, KvQueryResult.KeyModification> queryResultBytesToKeyModification = new Function<QueryResultBytes, KvQueryResult.KeyModification>() {
		public KvQueryResult.KeyModification apply(QueryResultBytes queryResultBytes) {
			try {
				return KvQueryResult.KeyModification.parseFrom(queryResultBytes.getResultBytes());
			} catch (InvalidProtocolBufferException e) {
				throw new RuntimeException(e);
			}
		};
	};

	@Override
	public Response invokeChaincode(final String chaincodeName, final List<byte[]> args, final String channel) {
		// internally we handle chaincode name as a composite name
		final String compositeName;
		if (channel != null && channel.trim().length() > 0) {
			compositeName = chaincodeName + "/" + channel;
		} else {
			compositeName = chaincodeName;
		}
		return handler.invokeChaincode(this.channelId, this.txId, compositeName, args);
	}

	@Override
	public SignedProposal getSignedProposal() {
		return signedProposal;
	}

	@Override
	public Instant getTxTimestamp() {
		return txTimestamp;
	}

	@Override
	public byte[] getCreator() {
		if(creator == null) return null;
		return creator.toByteArray();
	}

	@Override
	public Map<String, byte[]> getTransient() {
		return transientMap.entrySet().stream().collect(Collectors.toMap(x -> x.getKey(), x -> x.getValue().toByteArray()));
	}

	@Override
	public byte[] getBinding() {
		return this.binding;
	}
}
