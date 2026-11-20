package io.supportflow.customer.service.bot;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class AgentReplyArgumentsTest {
    static final UUID RUN = UUID.fromString("00000000-0000-0000-0000-000000000001");
    static final UUID EVENT = UUID.fromString("00000000-0000-0000-0000-000000000002");
    static final UUID SNAPSHOT = UUID.fromString("00000000-0000-0000-0000-000000000003");
    static AgentReplyArguments reply(String text) { return new AgentReplyArguments("TENANT_A",RUN,EVENT,SNAPSHOT,text); }

    @Test void fixedPythonJavaFingerprintUsesExactUtf8AndNeverLogsRawText() {
        var value = reply("答复\n\"exact\" ");
        assertEquals("78e607df16ab9a644dff1e4cec93312f716695ef270c89a786f768589afae522",value.argumentsSha256());
        assertFalse(value.toString().contains(value.content()));
        assertTrue(value.toString().contains("[redacted]"));
    }
    @Test void tenantRunSourceSnapshotAndExactContentCannotBeSubstituted() {
        var original=reply("exact ");
        for (var changed : new AgentReplyArguments[]{new AgentReplyArguments("TENANT_B",RUN,EVENT,SNAPSHOT,"exact "),
                new AgentReplyArguments("TENANT_A",UUID.randomUUID(),EVENT,SNAPSHOT,"exact "),
                new AgentReplyArguments("TENANT_A",RUN,UUID.randomUUID(),SNAPSHOT,"exact "),
                new AgentReplyArguments("TENANT_A",RUN,EVENT,UUID.randomUUID(),"exact "),reply("exact")})
            assertNotEquals(original.argumentsSha256(),changed.argumentsSha256());
        assertNotEquals(reply("é").argumentsSha256(),reply("e\u0301").argumentsSha256());
    }
    @Test void byteLimitsInvalidUnicodeNulAndBlankBodiesFailBeforeDatabaseAccess() {
        for (String text : new String[]{null,""," \r\n\t","\u3000","a\0b","x".repeat(8193),"中".repeat(2731),"\ud800"})
            assertThrows(IllegalArgumentException.class,()->reply(text));
        assertEquals(64,reply("x".repeat(8192)).argumentsSha256().length());
        assertEquals(64,reply("\u00a0").argumentsSha256().length());
    }
    @Test void platformAndMissingResourceReferencesAreRejected() {
        for (String tenant : new String[]{null,"","PLATFORM","platform","TENANT A"})
            assertThrows(IllegalArgumentException.class,()->new AgentReplyArguments(tenant,RUN,EVENT,SNAPSHOT,"exact"));
        assertThrows(IllegalArgumentException.class,()->new AgentReplyArguments("TENANT_A",null,EVENT,SNAPSHOT,"exact"));
        assertThrows(IllegalArgumentException.class,()->new AgentReplyArguments("TENANT_A",RUN,null,SNAPSHOT,"exact"));
        assertThrows(IllegalArgumentException.class,()->new AgentReplyArguments("TENANT_A",RUN,EVENT,null,"exact"));
    }
}
