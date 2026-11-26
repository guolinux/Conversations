package im.conversations.android.xmpp.sasl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import im.conversations.android.database.model.Account;
import im.conversations.android.database.model.Credential;
import im.conversations.android.tls.SSLSockets;
import java.util.List;
import org.jxmpp.jid.impl.JidCreate;
import org.junit.Test;

public class ChannelBindingPolicyTest {

    private final Account account =
            new Account(1, JidCreate.entityBareFromOrThrowUnchecked("user@example.org"), new byte[32]);

    @Test
    public void requiresChannelBindingOnlyWhenEnabled() {
        final var factory = new SaslMechanism.Factory(account, Credential.empty());
        final var mechanisms = List.of(ScramSha256.MECHANISM);
        final var version = SaslMechanism.Version.SASL;
        final var tlsVersion = SSLSockets.Version.TLS_1_3;

        assertNull(factory.of(mechanisms, List.of(), version, tlsVersion, true));
        assertEquals(
                ScramSha256.MECHANISM,
                factory.of(mechanisms, List.of(), version, tlsVersion, false).getMechanism());
    }

    @Test
    public void unboundFastTokenDoesNotPreventChannelBoundScram() {
        final var unboundToken =
                new HashedToken.Mechanism("SHA-256", ChannelBinding.NONE).name();
        final var credential =
                new Credential("password", false, null, null, unboundToken, "token", null, null);
        final var factory = new SaslMechanism.Factory(account, credential);
        final var result =
                factory.of(
                        List.of(ScramSha256Plus.MECHANISM),
                        List.of(ChannelBinding.TLS_EXPORTER),
                        SaslMechanism.Version.SASL_2,
                        SSLSockets.Version.TLS_1_3,
                        true);

        assertEquals(ScramSha256Plus.MECHANISM, result.getMechanism());
        assertEquals(
                ChannelBinding.TLS_EXPORTER, ((ChannelBindingMechanism) result).getChannelBinding());
    }
}
