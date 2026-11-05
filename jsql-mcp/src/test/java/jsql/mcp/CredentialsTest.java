package jsql.mcp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.junit.Test;

import jsql.mcp.Credentials.CredentialsException;

public class CredentialsTest {

    private static final Credentials FALLBACK = new Credentials("svc", "svcpw");

    private static String basic(String userAndPassword) {
        return "Basic " + Base64.getEncoder().encodeToString(userAndPassword.getBytes(StandardCharsets.UTF_8));
    }

    private static String rejected(String header, Credentials fallback) {
        try {
            Credentials.resolve(header, fallback);
            fail("expected CredentialsException for: " + header);
            return null;
        } catch (CredentialsException e) {
            return e.getMessage();
        }
    }

    @Test
    public void headerWinsOverFallback() {
        Credentials c = Credentials.resolve(basic("alice:s3cret"), FALLBACK);
        assertEquals("alice", c.user());
        assertEquals("s3cret", c.password());
    }

    @Test
    public void fallbackIsUsedWithoutHeader() {
        assertSame(FALLBACK, Credentials.resolve(null, FALLBACK));
        assertSame(FALLBACK, Credentials.resolve("", FALLBACK));
        assertSame(FALLBACK, Credentials.resolve("   ", FALLBACK));
    }

    @Test
    public void noHeaderAndNoFallbackExplainsWhatToDo() {
        String message = rejected(null, null);
        assertTrue(message, message.contains("Authorization: Basic"));
    }

    @Test
    public void schemeIsCaseInsensitiveAndWhitespaceTolerant() {
        String b64 = Base64.getEncoder().encodeToString("a:b".getBytes(StandardCharsets.UTF_8));
        assertEquals("a", Credentials.resolve("basic " + b64, null).user());
        assertEquals("a", Credentials.resolve("  BASIC   " + b64 + "  ", null).user());
    }

    @Test
    public void passwordMayContainColonsAndBeEmpty() {
        Credentials c = Credentials.resolve(basic("alice:pa:ss:wd"), null);
        assertEquals("pa:ss:wd", c.password());
        assertEquals("", Credentials.resolve(basic("alice:"), null).password());
    }

    @Test
    public void nonAsciiIsDecodedAsUtf8() {
        Credentials c = Credentials.resolve(basic("jörg:pässwörd"), null);
        assertEquals("jörg", c.user());
        assertEquals("pässwörd", c.password());
    }

    @Test
    public void otherSchemesAreRejectedEvenWithFallback() {
        // must not silently fall back to the default account
        assertTrue(rejected("Bearer abc.def", FALLBACK).contains("Only 'Basic'"));
        assertTrue(rejected("Digest x", FALLBACK).contains("Only 'Basic'"));
    }

    @Test
    public void malformedBasicHeadersAreRejected() {
        assertTrue(rejected("Basic", FALLBACK).contains("user:password"));
        assertTrue(rejected("Basic !!!notbase64", FALLBACK).contains("not valid base64"));
        assertTrue(rejected(basic("nocolon"), FALLBACK).contains("user:password"));
        assertTrue(rejected(basic(":pw"), FALLBACK).contains("user name"));
    }

    @Test
    public void passwordIsNotInToStringOrErrors() {
        Credentials c = new Credentials("alice", "s3cret");
        assertFalse(c.toString().contains("s3cret"));
        assertTrue(c.toString().contains("alice"));
        assertFalse(rejected("Basic !!!s3cret", null).contains("s3cret"));
    }
}
