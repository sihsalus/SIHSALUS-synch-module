package org.openmrs.module.synchronizationmr.sync;

import java.io.IOException;
import java.util.UUID;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.openmrs.api.APIException;
import org.openmrs.module.synchronizationmr.api.SyncAuditService;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class SyncAuditTest {

	private final SyncAuditService service = mock(SyncAuditService.class);

	private final SyncAudit audit = new SyncAudit(service);

	private ObjectNode event() {
		return new ObjectMapper().createObjectNode().put("entityType", "ORDER").put("originServerId", "posta_b")
		        .put("entitySequence", 12).put("eventUuid", UUID.randomUUID().toString()).put("secret", "No guardar");
	}

	@Test
	public void capturesEventOriginSeparatelyFromTransportSource() {
		ObjectNode e = event();
		SyncAuditRecord r = new SyncAuditRecord("maestro", "posta_a", "ORDER", "RECEIVE", e);
		assertEquals("maestro", r.source);
		assertEquals("posta_b", r.eventOrigin);
		assertEquals(12L, r.sequence.longValue());
		assertEquals(e.path("eventUuid").asText(), r.eventUuid);
	}

	@Test
	public void invalidMetadataDoesNotBecomeAnEventIdentity() {
		ObjectNode e = event().put("eventUuid", "texto clínico");
		SyncAuditRecord r = new SyncAuditRecord("maestro", "posta_a", "ORDER", "RECEIVE", e);
		assertNull(r.eventUuid);
		assertNull(r.sequence);
		assertNull(r.eventOrigin);
	}

	@Test public void failedSendAndSuccessfulRetryHaveSeparateAttempts() throws Exception {
        when(service.begin(any())).thenReturn("first","second");
        ObjectNode e=event();
        assertThrows(IOException.class,() -> audit.run("posta_a","maestro","ORDER","SEND",e,"CONFIRMED",
                () -> { throw new IOException("datos privados"); }));
        assertEquals(12,audit.run("posta_a","maestro","ORDER","SEND",e,"CONFIRMED",() -> 12).intValue());
        verify(service).finish("first","UNCONFIRMED","COMMUNICATION");
        verify(service).finish("second","CONFIRMED","OK");
    }

	@Test public void connectionFailureHasNoEvent() {
        when(service.begin(any())).thenReturn("query");
        assertThrows(IOException.class,() -> audit.run("posta_a","maestro","PATIENT","QUERY_NODE",null,"SUCCEEDED",
                () -> { throw new IOException(); }));
        org.mockito.ArgumentCaptor<SyncAuditRecord> capture=org.mockito.ArgumentCaptor.forClass(SyncAuditRecord.class);
        verify(service).begin(capture.capture()); assertNull(capture.getValue().eventUuid);
        verify(service).finish("query","FAILED","COMMUNICATION");
    }

	@Test public void receiverFailureIsRetainedWithoutExceptionText() {
        when(service.begin(any())).thenReturn("receive");
        assertThrows(APIException.class,() -> audit.run("maestro","posta_a","ORDER","RECEIVE",event(),"RECEIVED",
                () -> { throw new APIException("nombre y contraseña"); }));
        verify(service).finish("receive","FAILED","REJECTED");
    }

	@Test public void operationDoesNotRunIfInitialAuditCannotBeStored() {
        when(service.begin(any())).thenThrow(new APIException("base indisponible"));
        java.util.concurrent.atomic.AtomicBoolean ran=new java.util.concurrent.atomic.AtomicBoolean();
        assertThrows(APIException.class,() -> audit.run("a","m","ORDER","SEND",event(),"CONFIRMED",
                () -> { ran.set(true); return 1; }));
        assertFalse(ran.get());
    }

	@Test public void auditCloseFailureDoesNotRewriteSuccessfulOperationAsFailed() {
        when(service.begin(any())).thenReturn("attempt");
        doThrow(new APIException("base indisponible")).when(service).finish("attempt","RECEIVED","OK");
        assertThrows(APIException.class,() -> audit.run("m","a","ORDER","RECEIVE",event(),"RECEIVED",() -> 1));
        verify(service,times(1)).finish(any(),any(),any());
    }

	@Test public void originalFailureSurvivesFailureToCloseAudit() {
        when(service.begin(any())).thenReturn("attempt");
        doThrow(new APIException("base indisponible")).when(service).finish(any(),any(),any());
        IOException expected=new IOException("red");
        assertSame(expected,assertThrows(IOException.class,() -> audit.run("a","m","ORDER","SEND",event(),"CONFIRMED",
                () -> { throw expected; })));
    }
}
