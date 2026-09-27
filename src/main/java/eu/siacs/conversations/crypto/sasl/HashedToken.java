package eu.siacs.conversations.crypto.sasl;

import android.util.Log;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.Multimap;
import com.google.common.hash.HashFunction;
import com.google.common.io.ByteStreams;
import com.google.common.primitives.Bytes;
import eu.siacs.conversations.Config;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.utils.SSLSockets;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import javax.net.ssl.SSLSocket;

public abstract class HashedToken extends SaslMechanism implements ChannelBindingMechanism {

    private static final List<VersionHash> VERSION_HASH_PREFERENCE;
    private static final byte[] INITIATOR = "Initiator".getBytes(StandardCharsets.UTF_8);
    private static final byte[] RESPONDER = "Responder".getBytes(StandardCharsets.UTF_8);

    static {
        final var versionHashPreferenceBuilder = new ImmutableList.Builder<VersionHash>();
        for (final var version : Version.values()) {
            for (final var hashFunction : Arrays.asList("SHA-512", "SHA-256")) {
                versionHashPreferenceBuilder.add(new VersionHash(version, hashFunction));
            }
        }
        VERSION_HASH_PREFERENCE = versionHashPreferenceBuilder.build();
    }

    protected final Version version;
    protected final ChannelBinding channelBinding;

    protected HashedToken(
            final Account account, final Version version, final ChannelBinding channelBinding) {
        super(account);
        this.version = version;
        this.channelBinding = channelBinding;
    }

    @Override
    public int getPriority() {
        throw new UnsupportedOperationException();
    }

    @Override
    public byte[] getClientFirstMessage(final SSLSocket sslSocket) {
        final var token = Strings.nullToEmpty(this.account.getFastToken());
        final var hashing = getHashFunction(token.getBytes(StandardCharsets.UTF_8));
        final var cbData = getChannelBindingData(sslSocket);
        final byte[] initiatorHashedToken =
                hashing.hashBytes(Bytes.concat(INITIATOR, cbData)).asBytes();
        final var message = ByteStreams.newDataOutput();
        message.write(account.getUsername().getBytes(StandardCharsets.UTF_8));
        if (version == Version.HT2) {
            message.write(0);
        }
        message.write(0);
        message.write(initiatorHashedToken);
        return message.toByteArray();
    }

    protected byte[] getChannelBindingData(final SSLSocket sslSocket) {
        if (this.channelBinding == ChannelBinding.NONE) {
            return new byte[0];
        }
        try {
            return ChannelBindingMechanism.getChannelBindingData(sslSocket, this.channelBinding);
        } catch (final AuthenticationException e) {
            Log.e(
                    Config.LOGTAG,
                    account.getJid().asBareJid()
                            + ": unable to retrieve channel binding data for "
                            + getMechanism(),
                    e);
            return new byte[0];
        }
    }

    @Override
    public byte[] getResponse(final byte[] challenge, final SSLSocket socket)
            throws AuthenticationException {
        if (challenge == null || challenge.length == 0) {
            throw new AuthenticationException("HT response had invalid length");
        }
        final var token = Strings.nullToEmpty(this.account.getFastToken());
        final var hashing = getHashFunction(token.getBytes(StandardCharsets.UTF_8));
        final var cbData = getChannelBindingData(socket);
        if (version == Version.HT) {
            final byte[] expectedResponderMessage =
                    hashing.hashBytes(Bytes.concat(RESPONDER, cbData)).asBytes();
            if (MessageDigest.isEqual(challenge, expectedResponderMessage)) {
                return new byte[0];
            }
            throw new AuthenticationException("Responder message did not match");
        } else if (version == Version.HT2) {
            final var status = challenge[0];
            final byte[] message = Arrays.copyOfRange(challenge, 1, challenge.length);
            if (status == 0x00) {
                final int nullByteIndex = Bytes.indexOf(message, (byte) 0x00);
                if (nullByteIndex < 0) {
                    throw new AuthenticationException("Could not identify responder hashed token");
                }
                final var extraValues = Arrays.copyOfRange(message, 0, nullByteIndex);
                final var responderHashedToken =
                        Arrays.copyOfRange(message, nullByteIndex + 1, message.length);
                if (responderHashedToken.length == 0) {
                    throw new AuthenticationException("Responder hashed token is empty");
                }
                final byte[] expectedResponderMessage =
                        hashing.hashBytes(Bytes.concat(RESPONDER, cbData, extraValues)).asBytes();
                if (MessageDigest.isEqual(responderHashedToken, expectedResponderMessage)) {
                    return new byte[0];
                } else {
                    throw new AuthenticationException("Responder message did not match");
                }
            } else if (status == 0x01) {
                final var description = new String(message, StandardCharsets.UTF_8);
                throw new AuthenticationException("Failure response in HT2: " + description);
            } else {
                throw new AuthenticationException("Invalid status code in HT2 response");
            }
        } else {
            throw new AuthenticationException("Unsupported HT version");
        }
    }

    protected abstract HashFunction getHashFunction(final byte[] key);

    public abstract Mechanism getTokenMechanism();

    @Override
    public String getMechanism() {
        return getTokenMechanism().name();
    }

    public record Mechanism(Version version, String hashFunction, ChannelBinding channelBinding) {

        public static Mechanism of(final String mechanism) {
            final int first = mechanism.indexOf('-');
            final int last = mechanism.lastIndexOf('-');
            if (first == -1 || last == -1 || last < first) {
                throw new IllegalArgumentException("Not a valid HashedToken name");
            }
            final var version = Version.valueOf(mechanism.substring(0, first));
            final var hashFunction = mechanism.substring(first + 1, last);
            final var cbShortName = mechanism.substring(last + 1);
            final var channelBinding = ChannelBinding.SHORT_NAMES.inverse().get(cbShortName);
            if (channelBinding == null) {
                throw new IllegalArgumentException("Unknown channel binding " + cbShortName);
            }
            return new Mechanism(version, hashFunction, channelBinding);
        }

        public static Mechanism ofOrNull(final String mechanism) {
            try {
                return mechanism == null ? null : of(mechanism);
            } catch (final IllegalArgumentException e) {
                return null;
            }
        }

        private static Multimap<VersionHash, ChannelBinding> of(
                final Collection<String> mechanisms) {
            final ImmutableMultimap.Builder<VersionHash, ChannelBinding> builder =
                    ImmutableMultimap.builder();
            for (final String name : mechanisms) {
                try {
                    final Mechanism mechanism = Mechanism.of(name);
                    builder.put(
                            new VersionHash(mechanism.version, mechanism.hashFunction),
                            mechanism.channelBinding);
                } catch (final IllegalArgumentException ignored) {
                }
            }
            return builder.build();
        }

        public static Mechanism best(
                final Collection<String> mechanisms, final SSLSockets.Version sslVersion) {
            final Multimap<VersionHash, ChannelBinding> multimap = of(mechanisms);
            for (final var versionHash : VERSION_HASH_PREFERENCE) {
                final var channelBindings = multimap.get(versionHash);
                if (channelBindings.isEmpty()) {
                    continue;
                }
                final ChannelBinding cb = ChannelBinding.best(channelBindings, sslVersion);
                return new Mechanism(versionHash.version, versionHash.hash, cb);
            }
            return null;
        }

        public String name() {
            return String.format(
                    "%s-%s-%s",
                    version.name(), hashFunction, ChannelBinding.SHORT_NAMES.get(channelBinding));
        }
    }

    record VersionHash(Version version, String hash) {}

    public enum Version {
        HT2,
        HT
    }

    public ChannelBinding getChannelBinding() {
        return this.channelBinding;
    }
}
