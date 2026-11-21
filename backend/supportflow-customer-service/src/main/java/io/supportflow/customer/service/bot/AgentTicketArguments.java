package io.supportflow.customer.service.bot;

import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

/** Fixed internal-ticket policy; caller authority is separate from untrusted text. */
public record AgentTicketArguments(String tenant,UUID runId,UUID eventId,UUID snapshotId,String title,String content) {
    public static final String VERSION="ticket-create-v1";
    public static final int CATEGORY=5,PRIORITY=2,SOURCE_CHANNEL=1;
    public AgentTicketArguments {
        if(tenant==null || !tenant.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,15}") || tenant.equalsIgnoreCase("PLATFORM")
                || runId==null || eventId==null || snapshotId==null)throw new IllegalArgumentException("Invalid ticket references");
        bounded(title,128,512);bounded(content,8192,8192);
    }
    private static void bounded(String value,int points,int bytes){
        if(value==null || value.isBlank() || value.indexOf('\0')>=0 || value.codePointCount(0,value.length())>points)
            throw new IllegalArgumentException("Invalid ticket text");
        try{if(StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(value)).remaining()>bytes)throw new IllegalArgumentException("Ticket text exceeds byte limit");}
        catch(java.nio.charset.CharacterCodingException invalid){throw new IllegalArgumentException("Invalid ticket text");}
    }
    public String creatorRef(){return "agent-run:"+runId;}
    public String argumentsSha256(){return digest(String.join("\n","supportflow-ticket-create:v1",tenant,runId.toString(),eventId.toString(),snapshotId.toString(),
        digest(title),digest(content),Integer.toString(CATEGORY),Integer.toString(PRIORITY),Integer.toString(SOURCE_CHANNEL)));}
    private static String digest(String value){
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.GeneralSecurityException unavailable){throw new IllegalStateException("Ticket digest unavailable");}
    }
    @Override public String toString(){return "AgentTicketArguments[references only]";}
}
