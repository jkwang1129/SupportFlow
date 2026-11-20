package io.supportflow.customer.service.ticket;

import io.supportflow.customer.entity.TicketEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.time.LocalDateTime;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TicketTimestampTest {
    // Exercise the actual event DTO conversion used by platform detail/claim.
    // This path does not use persistence collaborators.
    private final TicketService service=mock(TicketService.class,CALLS_REAL_METHODS);
    @ParameterizedTest
    @CsvSource({"2026-11-20T15:41,2026-11-20 15:41:00", "2026-11-20T00:00,2026-11-20 00:00:00",
            "2028-02-29T23:59:00.987654321,2028-02-29 23:59:00", "2026-11-20T15:41:59.999,2026-11-20 15:41:59"})
    void eventTimelineAlwaysIncludesSecondsWithoutRounding(String input,String expected){
        var event=new TicketEvent();event.setId(1L);event.setEventTime(LocalDateTime.parse(input));
        assertEquals(expected,service.toEventVO(event).eventTime());
    }
    @Test void absentTimestampRemainsNull(){
        assertNull(service.toEventVO(new TicketEvent()).eventTime());
    }
}
