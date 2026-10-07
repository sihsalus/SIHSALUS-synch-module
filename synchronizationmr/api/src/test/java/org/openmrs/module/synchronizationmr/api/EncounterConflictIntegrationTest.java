package org.openmrs.module.synchronizationmr.api;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.time.Instant;
import java.util.*;
import liquibase.Liquibase;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.FileSystemResourceAccessor;
import org.junit.jupiter.api.*;
import org.openmrs.*;
import org.openmrs.api.*;
import org.openmrs.api.context.Context;
import org.openmrs.module.synchronizationmr.advice.*;
import org.openmrs.module.synchronizationmr.sync.*;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;
import org.springframework.test.context.transaction.TestTransaction;
import static org.junit.jupiter.api.Assertions.*;

public class EncounterConflictIntegrationTest extends BaseModuleContextSensitiveTest {

	@Test
	public void textReplacementIsCapturedWithoutDiscardingOriginalNote() throws Exception {
		Concept text = new Concept();
		text.addName(new ConceptName("Texto ficticio " + origin, Locale.ENGLISH));
		text.setDatatype(Context.getConceptService().getConceptDatatypeByName("Text"));
		text.setConceptClass(Context.getConceptService().getConceptClass(1));
		text = Context.getConceptService().saveConcept(text);
		Encounter e = sample();
		Obs old = obs(e, 0);
		old.setValueNumeric(null);
		old.setConcept(text);
		old.setValueText("Nota inicial");
		e.addObs(old);
		Context.getEncounterService().saveEncounter(e);
		long before = sync().getHighestEncounterSequence("testServer_1");
		old.setVoided(true);
		old.setVoidReason("Texto corregido");
		Obs next = obs(e, 0);
		next.setValueNumeric(null);
		next.setConcept(text);
		next.setValueText("Nota corregida");
		e.addObs(next);
		Context.getEncounterService().saveEncounter(e);
		ObservationCorrectionEvent event = new ObservationCorrectionEvent(sync()
		        .getEncounterEventsAfter("testServer_1", before, 100).get(0).getPayloadJson());
		assertEquals(old.getUuid(), event.observations().get(0).path("previousVersionUuid").asText());
		assertEquals("Nota inicial", Context.getObsService().getObsByUuid(old.getUuid()).getValueText());
		assertEquals("Nota corregida", stored(e.getUuid()).getObs().iterator().next().getValueText());
	}

	@Test
	public void differentFormFieldDoesNotInventReplacement() {
		Encounter e = sample();
		Obs old = obs(e, 62);
		old.setFormField("form", "field-one");
		e.addObs(old);
		Context.getEncounterService().saveEncounter(e);
		old.setVoided(true);
		old.setVoidReason("Retirado");
		Obs next = obs(e, 70);
		next.setFormField("form", "field-two");
		e.addObs(next);
		Context.getEncounterService().saveEncounter(e);
		assertNull(Context.getObsService().getObsByUuid(next.getUuid()).getPreviousVersion());
	}

	@Test
	public void sameFormFieldReplacementKeepsItsLineage() {
		Encounter e = sample();
		Obs old = obs(e, 62);
		old.setFormField("form", "field-one");
		e.addObs(old);
		Context.getEncounterService().saveEncounter(e);
		old.setVoided(true);
		old.setVoidReason("Corregido");
		Obs next = obs(e, 70);
		next.setFormField("form", "field-one");
		e.addObs(next);
		Context.getEncounterService().saveEncounter(e);
		assertEquals(old.getUuid(), Context.getObsService().getObsByUuid(next.getUuid()).getPreviousVersion().getUuid());
	}

	@Test public void foreignLateAdditionDoesNotAdvanceReceipt() throws Exception {
        Encounter e=imported();receive().receiveEncounter(annulment(e));Obs next=obs(e,70);
        ObjectNode bad=(ObjectNode)m.readTree(addition(e,next,origin+"edit",1));((ObjectNode)bad.path("payload")).put("patientUuid",UUID.randomUUID().toString());
        assertThrows(APIException.class,()->receive().receiveEncounter(bad.toString()));
        assertTrue(stored(e.getUuid()).getVoided());assertNull(Context.getObsService().getObsByUuid(next.getUuid()));assertEquals(0,receive().getConfirmedEncounterSequence(origin+"edit"));
    }

	private final EncounterCreationAdvice encounterAdvice = new EncounterCreationAdvice();

	private final ObservationAdditionAdvice obsAdvice = new ObservationAdditionAdvice();

	private final ObjectMapper m = new ObjectMapper();

	private final String origin = "void_" + UUID.randomUUID().toString().replace("-", "");

	private final Instant base = Instant.parse("2026-01-01T00:00:00Z");

	@BeforeEach
	public void prepare() throws Exception {
		new Liquibase("src/main/resources/liquibase.xml", new FileSystemResourceAccessor(), new JdbcConnection(
		        getConnection())).update("");
		Context.getAdministrationService().saveGlobalProperty(new GlobalProperty("server.id", "testServer_1"));
		Context.addAdvice(EncounterService.class, encounterAdvice);
		Context.addAdvice(ObsService.class, obsAdvice);
	}

	@AfterEach
	public void clean() {
		Context.removeAdvice(EncounterService.class, encounterAdvice);
		Context.removeAdvice(ObsService.class, obsAdvice);
	}

	private EncounterSyncService sync() {
		return Context.getService(EncounterSyncService.class);
	}

	private EncounterReceiveService receive() {
		return Context.getService(EncounterReceiveService.class);
	}

	private Encounter sample() {
		Encounter e = new Encounter();
		e.setPatient(Context.getPatientService().getPatient(2));
		e.setEncounterType(Context.getEncounterService().getEncounterType(1));
		e.setLocation(Context.getLocationService().getLocation(1));
		e.setEncounterDatetime(Date.from(base));
		return e;
	}

	private Obs obs(Encounter e, double value) {
		Obs o = new Obs();
		o.setPerson(e.getPatient());
		o.setEncounter(e);
		o.setConcept(Context.getConceptService().getConcept(5089));
		o.setLocation(e.getLocation());
		o.setObsDatetime(e.getEncounterDatetime());
		o.setValueNumeric(value);
		return o;
	}

	private Encounter imported() {
		Encounter e = sample();
		e.addObs(obs(e, 62));
		receive().receiveEncounter(
		    new EncounterCreationPayloadSerializer().serialize(e, origin, 1, UUID.randomUUID().toString(), Date.from(base)));
		return Context.getEncounterService().getEncounterByUuid(e.getUuid());
	}

	private final EncounterCreationPayloadSerializer serializer = new EncounterCreationPayloadSerializer();

	private Encounter stored(String id) {
		Context.flushSession();
		Context.clearSession();
		return Context.getEncounterService().getEncounterByUuid(id);
	}

	private Obs replacement(Encounter e, Obs prior, double value) {
		prior.setVoided(true);
		prior.setVoidReason("Correccion del formulario");
		prior.setDateVoided(new Date());
		prior.setVoidedBy(Context.getAuthenticatedUser());
		Obs next = obs(e, value);
		e.addObs(next);
		Context.getEncounterService().saveEncounter(e);
		return next;
	}

	private String correction(Encounter e, Obs prior, double value, String source, Instant time) {
		Obs next = Obs.newInstance(prior);
		next.setVoided(false);
		next.setVoidReason(null);
		next.setPreviousVersion(prior);
		next.setValueNumeric(value);
		ObjectNode item = serializer.observationVersion(next);
		item.put("rootUuid", prior.getUuid());
		return ObservationCorrectionEvent.create(e, m.createArrayNode().add(item),
		    Collections.singletonMap(prior.getUuid(), next.getUuid()), source, 1, time);
	}

	private String addition(Encounter e, Obs next, String source, long seq) {
		return serializer.serializeAddition(e, Collections.singletonList(next), Collections.singleton(next.getUuid()),
		    source, seq, UUID.randomUUID().toString(), Date.from(base.plusSeconds(30)));
	}

	private String annulment(Encounter e) {
		Encounter copy = new Encounter();
		copy.setUuid(e.getUuid());
		copy.setPatient(e.getPatient());
		copy.setVoidReason("Anulacion de prueba");
		copy.setDateVoided(Date.from(base.plusSeconds(20)));
		return EncounterVoidEvent.create(copy, origin + "void", 1, base.plusSeconds(20));
	}

	@Test
	public void formReplacementBecomesOneCorrectionWithExplicitLineage() throws Exception {
		Encounter e = imported();
		Obs old = e.getObs().iterator().next();
		long before = sync().getHighestEncounterSequence("testServer_1");
		Obs next = replacement(e, old, 70);
		List<EncounterSyncEvent> events = sync().getEncounterEventsAfter("testServer_1", before, 100);
		assertEquals(1, events.size());
		ObservationCorrectionEvent event = new ObservationCorrectionEvent(events.get(0).getPayloadJson());
		assertEquals(old.getUuid(), event.observations().get(0).path("previousVersionUuid").asText());
		assertEquals(next.getUuid(), event.heads.get(old.getUuid()));
		e = stored(e.getUuid());
		assertEquals(1, e.getObs().size());
		assertEquals(70, e.getObs().iterator().next().getValueNumeric());
		assertEquals(62, Context.getObsService().getObsByUuid(old.getUuid()).getValueNumeric());
		Context.getEncounterService().saveEncounter(e);
		assertEquals(before + 1, sync().getHighestEncounterSequence("testServer_1"));
	}

	@Test
	public void threeBranchesChooseNewestRegardlessOfDeliveryOrderFirst() throws Exception {
		threeBranchesChooseNewestRegardlessOfDeliveryOrder(true);
	}

	@Test
	public void threeBranchesChooseNewestRegardlessOfDeliveryOrderLast() throws Exception {
		threeBranchesChooseNewestRegardlessOfDeliveryOrder(false);
	}

	private void threeBranchesChooseNewestRegardlessOfDeliveryOrder(boolean newestFirst) throws Exception {
		Encounter e = imported();
		Obs old = e.getObs().iterator().next();
		long before = sync().getHighestEncounterSequence("testServer_1");
		replacement(e, old, 39);
		ObservationCorrectionEvent local = new ObservationCorrectionEvent(sync()
		        .getEncounterEventsAfter("testServer_1", before, 100).get(0).getPayloadJson());
		String a = correction(e, old, 38, origin + "a", local.occurredAt.plusSeconds(1));
		String b = correction(e, old, 36.5, origin + "b", local.occurredAt.plusSeconds(2));
		receive().receiveEncounter(newestFirst ? b : a);
		receive().receiveEncounter(newestFirst ? a : b);
		receive().receiveEncounter(b);
		e = stored(e.getUuid());
		assertEquals(1, e.getObs().size());
		assertEquals(36.5, e.getObs().iterator().next().getValueNumeric());
		assertEquals(4, e.getAllObs(true).size());
		assertEquals(1, receive().getConfirmedEncounterSequence(origin + "a"));
		assertEquals(1, receive().getConfirmedEncounterSequence(origin + "b"));
	}

	@Test
	public void genuineAdditionDoesNotBecomeCorrection() {
		Encounter e = imported();
		Obs next = obs(e, 70);
		e.addObs(next);
		Context.getEncounterService().saveEncounter(e);
		e = stored(e.getUuid());
		assertEquals(2, e.getObs().size());
		assertNull(Context.getObsService().getObsByUuid(next.getUuid()).getPreviousVersion());
	}

	@Test
	public void ambiguousPairingDoesNotInventLineage() {
		Encounter e = imported();
		Obs old = e.getObs().iterator().next();
		e.addObs(obs(e, 61));
		Context.getEncounterService().saveEncounter(e);
		Obs next = replacement(e, old, 70);
		assertNull(Context.getObsService().getObsByUuid(next.getUuid()).getPreviousVersion());
	}

	@Test
	public void separateVoidAndAdditionAreNotPairedByTime() {
		Encounter e = imported();
		Obs old = e.getObs().iterator().next();
		Context.getObsService().voidObs(old, "Anulacion independiente");
		Obs next = obs(e, 70);
		e.addObs(next);
		Context.getEncounterService().saveEncounter(e);
		assertNull(Context.getObsService().getObsByUuid(next.getUuid()).getPreviousVersion());
	}

	@Test
	public void changedMeasurementDateIsNotMatched() {
		Encounter e = imported();
		Obs old = e.getObs().iterator().next();
		old.setVoided(true);
		old.setVoidReason("Retirado");
		Obs next = obs(e, 70);
		next.setObsDatetime(Date.from(base.plusSeconds(1)));
		e.addObs(next);
		Context.getEncounterService().saveEncounter(e);
		assertNull(Context.getObsService().getObsByUuid(next.getUuid()).getPreviousVersion());
	}

	@Test
	public void replacementRollbackRestoresNativeHistoryAndCounter() {
		Encounter e = imported();
		String id = e.getUuid();
		TestTransaction.flagForCommit();
		TestTransaction.end();
		TestTransaction.start();
		long before = sync().getHighestEncounterSequence("testServer_1");
		e = stored(id);
		replacement(e, e.getObs().iterator().next(), 70);
		TestTransaction.flagForRollback();
		TestTransaction.end();
		TestTransaction.start();
		e = stored(id);
		assertEquals(1, e.getAllObs(true).size());
		assertEquals(62, e.getObs().iterator().next().getValueNumeric());
		assertEquals(before, sync().getHighestEncounterSequence("testServer_1"));
	}

	@Test
	public void additionAndVoidConvergeWithoutBlockingFollowingEventFirst() {
		additionAndVoidConvergeWithoutBlockingFollowingEvent(true);
	}

	@Test
	public void additionAndVoidConvergeWithoutBlockingFollowingEventLast() {
		additionAndVoidConvergeWithoutBlockingFollowingEvent(false);
	}

	private void additionAndVoidConvergeWithoutBlockingFollowingEvent(boolean voidFirst) {
		Encounter e = imported();
		String id = e.getUuid();
		Obs next = obs(e, 70);
		String add = addition(e, next, origin + "edit", 1), del = annulment(e);
		receive().receiveEncounter(voidFirst ? del : add);
		receive().receiveEncounter(voidFirst ? add : del);
		receive().receiveEncounter(add);
		e = stored(id);
		assertTrue(e.getVoided());
		assertEquals(0, e.getObs().size());
		Obs saved = Context.getObsService().getObsByUuid(next.getUuid());
		assertTrue(saved.getVoided());
		assertEquals(70, saved.getValueNumeric());
		Obs other = obs(e, 71);
		receive().receiveEncounter(addition(e, other, origin + "edit", 2));
		assertEquals(2, receive().getConfirmedEncounterSequence(origin + "edit"));
		assertTrue(stored(id).getVoided());
		assertTrue(Context.getObsService().getObsByUuid(other.getUuid()).getVoided());
	}

	@Test
	public void correctionAndVoidConvergeKeepingValueAsHistoryFirst() {
		correctionAndVoidConvergeKeepingValueAsHistory(true);
	}

	@Test
	public void correctionAndVoidConvergeKeepingValueAsHistoryLast() {
		correctionAndVoidConvergeKeepingValueAsHistory(false);
	}

	private void correctionAndVoidConvergeKeepingValueAsHistory(boolean voidFirst) {
        Encounter e=imported();Obs old=e.getObs().iterator().next();String del=annulment(e),edit=correction(e,old,70,origin+"edit",base.plusSeconds(30));
        receive().receiveEncounter(voidFirst?del:edit);receive().receiveEncounter(voidFirst?edit:del);
        e=stored(e.getUuid());assertTrue(e.getVoided());assertEquals(2,e.getAllObs(true).size());assertEquals(0,e.getObs().size());
        assertTrue(e.getAllObs(true).stream().anyMatch(o->Double.valueOf(70).equals(o.getValueNumeric())));
        assertEquals(1,receive().getConfirmedEncounterSequence(origin+"edit"));
    }

	@Test
	public void metadataAndVoidConvergeWithoutReactivationFirst() {
		metadataAndVoidConvergeWithoutReactivation(true);
	}

	@Test
	public void metadataAndVoidConvergeWithoutReactivationLast() {
		metadataAndVoidConvergeWithoutReactivation(false);
	}

	private void metadataAndVoidConvergeWithoutReactivation(boolean voidFirst) {
		Encounter e = imported();
		String del = annulment(e);
		ObjectNode payload = EncounterUpdateEvent.capture(e);
		payload.put("locationUuid", Context.getLocationService().getLocation(2).getUuid());
		String edit = EncounterUpdateEvent.create(payload, Collections.singleton("locationUuid"), origin + "edit", 1,
		    base.plusSeconds(30));
		receive().receiveEncounter(voidFirst ? del : edit);
		receive().receiveEncounter(voidFirst ? edit : del);
		e = stored(e.getUuid());
		assertTrue(e.getVoided());
		assertEquals(2, e.getLocation().getId());
		assertEquals(1, receive().getConfirmedEncounterSequence(origin + "edit"));
	}

	@Test
	public void lateEditRollbackDoesNotAdvanceReceiptOrLoseAnnulment() {
		Encounter e = imported();
		receive().receiveEncounter(annulment(e));
		String id = e.getUuid();
		Obs next = obs(e, 70);
		String add = addition(e, next, origin + "edit", 1);
		TestTransaction.flagForCommit();
		TestTransaction.end();
		TestTransaction.start();
		receive().receiveEncounter(add);
		TestTransaction.flagForRollback();
		TestTransaction.end();
		TestTransaction.start();
		assertTrue(stored(id).getVoided());
		assertNull(Context.getObsService().getObsByUuid(next.getUuid()));
		assertEquals(0, receive().getConfirmedEncounterSequence(origin + "edit"));
	}
}
