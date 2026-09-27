package eu.siacs.conversations.crypto.sasl;

import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;
import eu.siacs.conversations.entities.Account;

public class HashedTokenSha256 extends HashedToken {

    public HashedTokenSha256(
            final Account account, final Version version, final ChannelBinding channelBinding) {
        super(account, version, channelBinding);
    }

    @Override
    protected HashFunction getHashFunction(final byte[] key) {
        return Hashing.hmacSha256(key);
    }

    @Override
    public Mechanism getTokenMechanism() {
        return new Mechanism(version, "SHA-256", channelBinding);
    }
}
