package org.openmrs.module.synchronizationmr.api;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.*;
import liquibase.Liquibase;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.FileSystemResourceAccessor;
import org.junit.jupiter.api.*;
import org.openmrs.*;
import org.openmrs.api.*;
import org.openmrs.api.context.Context;
import org.openmrs.module.synchronizationmr.advice.PatientCreationAdvice;
import org.openmrs.module.synchronizationmr.sync.*;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;
import org.springframework.test.context.transaction.TestTransaction;
import static org.junit.jupiter.api.Assertions.*;

public class PatientUpdateIntegrationTest extends BaseModuleContextSensitiveTest {
	
	private PatientCreationAdvice advice;
	
	private long localBefore;
	
	private final String prefix = "upd_" + UUID.randomUUID().toString().replace("-", "") + "_";
	
	private String origin(String value) {
		return prefix + value;
	}
	
	private final ObjectMapper mapper = new ObjectMapper();
	
	private static final Instant BASE = Instant.parse("2026-01-01T00:00:00Z");
	
	@BeforeEach
	public void prepare() throws Exception {
		new Liquibase("src/main/resources/liquibase.xml", new FileSystemResourceAccessor(), new JdbcConnection(
		        getConnection())).update("");
		Context.getAdministrationService().saveGlobalProperty(new GlobalProperty("server.id", "testServer_1"));
		advice = new PatientCreationAdvice();
		Context.addAdvice(PatientService.class, advice);
		Context.addAdvice(PersonService.class, advice);
		localBefore = sync().getHighestPatientSequence("testServer_1");
	}
	
	@AfterEach
	public void cleanup() {
		Context.removeAdvice(PatientService.class, advice);
		Context.removeAdvice(PersonService.class, advice);
	}
	
	private PatientSyncService sync() {
		return Context.getService(PatientSyncService.class);
	}
	
	private PatientReceiveService receive() {
		return Context.getService(PatientReceiveService.class);
	}
	
	private Patient sample() {
		Patient patient = new Patient();
		patient.setGender("F");
		patient.setBirthdate(java.sql.Date.valueOf("1991-08-15"));
		PersonName name = new PersonName("Prueba", null, "Paciente");
		name.setPreferred(true);
		patient.addName(name);
		PatientIdentifier identifier = new PatientIdentifier("UPD-" + UUID.randomUUID(), Context.getPatientService()
		        .getPatientIdentifierType(2), Context.getLocationService().getLocation(1));
		identifier.setPreferred(true);
		patient.addIdentifier(identifier);
		PersonAddress address = new PersonAddress();
		address.setAddress1("Direccion inicial");
		address.setPreferred(true);
		patient.addAddress(address);
		return patient;
	}
	
	private String create(Patient patient, String origin) {
		String json = new PatientCreationPayloadSerializer().serialize(patient, origin(origin), 1, UUID.randomUUID()
		        .toString(), Date.from(BASE));
		assertEquals(1, receive().receivePatient(json));
		return json;
	}
	
	private String update(Patient values, String origin, long sequence, int seconds, String... groups) {
        return PatientUpdateEvent.create(PatientSnapshot.capture(values).toJson(), new LinkedHashSet<>(Arrays.asList(groups)),
            origin(origin), sequence, BASE.plusSeconds(seconds));
    }
	
	private Patient stored(Patient source) {
		Context.flushSession();
		Context.clearSession();
		return Context.getPatientService().getPatientByUuid(source.getUuid());
	}
	
	@Test
	public void localSaveAppendsUpdateWithoutChangingIdentityOrCreationAndSkipsNoop() throws Exception {
		Patient saved = Context.getPatientService().savePatient(sample());
		PatientSyncRecord identity = sync().getByPatientUuid(saved.getUuid());
		String original = sync().getCreationPayload(saved.getUuid());
		saved.getPersonAddress().setAddress1("Direccion corregida");
		Context.getPatientService().savePatient(saved);
		assertEquals(identity.getSequence() + 1, sync().getHighestPatientSequence("testServer_1"));
		assertEquals(identity.getSequence(), sync().getByPatientUuid(saved.getUuid()).getSequence());
		assertEquals(original, sync().getCreationPayload(saved.getUuid()));
		PatientSyncEvent change = sync().getPatientEventsAfter("testServer_1", identity.getSequence(), 1).get(0);
		PatientUpdateEvent parsed = new PatientUpdateEvent(change.getPayloadJson());
		assertEquals(Collections.singleton("addresses"), parsed.changedGroups);
		assertEquals(saved.getUuid(), parsed.patientUuid);
		Context.getPatientService().savePatient(saved);
		assertEquals(identity.getSequence() + 1, sync().getHighestPatientSequence("testServer_1"));
	}
	
	@Test
    public void repeatedAddressValuesWithDifferentUuidsSurviveReceptionAndLaterLocalEdit() {
        Patient source = sample();
        String original = create(source, "address_source");
        Set<PersonAddress> addresses = new LinkedHashSet<>();
        for (int index = 0; index < 3; index++) {
            PersonAddress address = new PersonAddress();
            address.setAddress1("Calle Prueba 200");
            address.setPreferred(index == 2);
            address.setPerson(source);
            addresses.add(address);
        }
        source.setAddresses(addresses);
        String change = update(source, "address_source", 2, 10, "addresses");
        assertEquals(2, receive().receivePatient(change));
        Patient patient = stored(source);
        Set<String> expected = new HashSet<>();
        for (PersonAddress address : addresses) { expected.add(address.getUuid()); }
        Set<String> actual = new HashSet<>();
        for (PersonAddress address : patient.getAddresses()) {
            if (!address.getVoided()) { actual.add(address.getUuid()); }
        }
        assertEquals(expected, actual);
        assertEquals(2, receive().receivePatient(change));
        long beforeEdit = sync().getHighestPatientSequence("testServer_1");
        patient.getPersonName().setMiddleName("Sinconexion");
        Context.getPatientService().savePatient(patient);
        patient = stored(source);
        assertEquals("Sinconexion", patient.getPersonName().getMiddleName());
        assertEquals(beforeEdit + 1, sync().getHighestPatientSequence("testServer_1"));
        PatientUpdateEvent event = new PatientUpdateEvent(sync().getPatientEventsAfter("testServer_1", beforeEdit, 1).get(0).getPayloadJson());
        assertEquals(Collections.singleton("names"), event.changedGroups);
        assertEquals(3, event.payload().path("addresses").size());
        assertEquals(original, sync().getCreationPayload(source.getUuid()));
    }
	
	@Test
	public void personServiceSaveCapturesTheEditWithoutCreatingAnotherPatient() {
		Patient saved = Context.getPatientService().savePatient(sample());
		long initial = sync().getHighestPatientSequence("testServer_1");
		Person person = Context.getPersonService().getPerson(saved.getPersonId());
		person.getPersonName().setGivenName("Persona corregida");
		Context.getPersonService().savePerson(person);
		assertEquals(initial + 1, sync().getHighestPatientSequence("testServer_1"));
		PatientUpdateEvent event = new PatientUpdateEvent(sync().getPatientEventsAfter("testServer_1", initial, 1).get(0)
		        .getPayloadJson());
		assertTrue(event.changedGroups.contains("names"));
		assertEquals("Persona corregida", event.payload().path("names").get(0).path("givenName").asText());
	}
	
	@Test
	public void importedPatientCanBeEditedLocallyAndListedUnderTheEditingOrigin() {
		Patient source = sample();
		create(source, "other");
		Patient patient = stored(source);
		patient.getPersonName().setGivenName("Cambio local");
		Context.getPatientService().savePatient(patient);
		assertEquals(origin("other"), sync().getByPatientUuid(source.getUuid()).getOriginServerId());
		assertEquals(localBefore + 1, sync().getHighestPatientSequence("testServer_1"));
		assertTrue(sync().getPatientOrigins(null, 100).contains("testServer_1"));
		assertEquals(source.getUuid(), sync().getPatientEventsAfter("testServer_1", localBefore, 1).get(0).getPatientUuid());
	}
	
	@Test
    public void receptionIsIdempotentAndKeepsOriginalPatientAndEvent() throws Exception {
        Patient source = sample(); String original = create(source, "a");
        int patientId = stored(source).getPatientId();
        source.getPersonAddress().setAddress1("Direccion nueva");
        String change = update(source, "a", 2, 10, "addresses");
        assertEquals(2, receive().receivePatient(change));
        assertEquals(2, receive().receivePatient(change));
        Patient saved = stored(source);
        assertEquals(patientId, saved.getPatientId());
        assertEquals("Direccion nueva", saved.getPersonAddress().getAddress1());
        assertEquals(original, sync().getCreationPayload(source.getUuid()));
        assertEquals(localBefore, sync().getHighestPatientSequence("testServer_1"));
        assertEquals(2, sync().getPatientEventsAfter(origin("a"), 0, 10).size());
        ObjectNode corrupt = (ObjectNode) mapper.readTree(change);
        corrupt.put("eventUuid", UUID.randomUUID().toString());
        assertThrows(APIException.class, () -> receive().receivePatient(corrupt.toString()));
    }
	
	@Test
	public void olderUpdateIsPreservedAndConfirmedWithoutOverwritingNewerValue() {
		Patient source = sample();
		create(source, "creator");
		source.getPersonName().setGivenName("Reciente");
		String recent = update(source, "b", 1, 20, "names");
		source.getPersonName().setGivenName("Antiguo");
		String old = update(source, "a", 1, 10, "names");
		receive().receivePatient(recent);
		receive().receivePatient(old);
		assertEquals("Reciente", stored(source).getPersonName().getGivenName());
		assertEquals(1, receive().getConfirmedPatientSequence(origin("a")));
		assertEquals(old, sync().getPatientEventsAfter(origin("a"), 0, 1).get(0).getPayloadJson());
	}
	
	@Test
	public void equalTimestampHasSameWinnerInEitherDeliveryOrder() {
		for (boolean reverse : new boolean[] { false, true }) {
			Patient source = sample();
			String suffix = reverse ? "2" : "1";
			create(source, "creator" + suffix);
			source.getPersonName().setGivenName("A");
			String a = update(source, "a" + suffix, 1, 20, "names");
			source.getPersonName().setGivenName("B");
			String b = update(source, "b" + suffix, 1, 20, "names");
			receive().receivePatient(reverse ? b : a);
			receive().receivePatient(reverse ? a : b);
			assertEquals("B", stored(source).getPersonName().getGivenName());
		}
	}
	
	@Test
	public void independentGroupsPreserveBothEditsEvenWithStaleSnapshots() {
		Patient source = sample();
		create(source, "creator");
		source.getPersonName().setGivenName("Nombre nuevo");
		String names = update(source, "a", 1, 10, "names");
		source.getPersonName().setGivenName("Prueba");
		source.getPersonAddress().setAddress1("Direccion nueva");
		String address = update(source, "b", 1, 20, "addresses");
		receive().receivePatient(address);
		receive().receivePatient(names);
		Patient saved = stored(source);
		assertEquals("Nombre nuevo", saved.getPersonName().getGivenName());
		assertEquals("Direccion nueva", saved.getPersonAddress().getAddress1());
	}
	
	@Test
	public void removedAddressIsVoidedNotDeletedAndNewAddressKeepsItsUuid() {
		Patient source = sample();
		create(source, "a");
		String oldUuid = source.getPersonAddress().getUuid();
		source.getPersonAddress().setVoided(true);
		PersonAddress replacement = new PersonAddress();
		replacement.setAddress1("Reemplazo");
		replacement.setPreferred(true);
		source.addAddress(replacement);
		receive().receivePatient(update(source, "a", 2, 10, "addresses"));
		Patient saved = stored(source);
		assertEquals(2, saved.getAddresses().size());
		assertTrue(Context.getPersonService().getPersonAddressByUuid(oldUuid).getVoided());
		assertEquals(replacement.getUuid(), saved.getPersonAddress().getUuid());
	}
	
	@Test
    public void updateCannotStealAChildUuidFromAnotherPatient() {
        Patient first = sample(), second = sample(); create(first, "a"); create(second, "b");
        first.getPersonAddress().setUuid(second.getPersonAddress().getUuid());
        String malformed = update(first, "a", 2, 10, "addresses");
        assertThrows(APIException.class, () -> receive().receivePatient(malformed));
        assertEquals(1, receive().getConfirmedPatientSequence(origin("a")));
    }
	
	@Test
    public void missingPatientAndSequenceGapsDoNotAdvanceReceipts() {
        Patient source = sample();
        assertThrows(APIException.class, () -> receive().receivePatient(update(source, "a", 1, 10, "names")));
        assertEquals(0, receive().getConfirmedPatientSequence(origin("a")));
        create(source, "a");
        assertThrows(APIException.class, () -> receive().receivePatient(update(source, "a", 3, 10, "names")));
        assertEquals(1, receive().getConfirmedPatientSequence(origin("a")));
    }
	
	@Test
	public void clinicalChangesEventAndReceiptRollbackTogether() {
		Patient source = sample();
		create(source, "a");
		TestTransaction.flagForCommit();
		TestTransaction.end();
		TestTransaction.start();
		source.getPersonName().setGivenName("No confirmar");
		receive().receivePatient(update(source, "a", 2, 10, "names"));
		TestTransaction.flagForRollback();
		TestTransaction.end();
		TestTransaction.start();
		assertEquals("Prueba", stored(source).getPersonName().getGivenName());
		assertEquals(1, receive().getConfirmedPatientSequence(origin("a")));
		assertEquals(1, sync().getHighestPatientSequence(origin("a")));
	}
	
	@Test
	public void standaloneNameAddressAndIdentifierServicesCaptureChanges() {
		Patient saved = Context.getPatientService().savePatient(sample());
		long initial = sync().getHighestPatientSequence("testServer_1");
		PersonName name = saved.getPersonName();
		name.setGivenName("Nombre directo");
		Context.getPersonService().savePersonName(name);
		assertEquals(initial + 1, sync().getHighestPatientSequence("testServer_1"));
		PersonAddress address = Context.getPersonService().getPersonAddressByUuid(saved.getPersonAddress().getUuid());
		address.setAddress1("Direccion directa");
		Context.getPersonService().savePersonAddress(address);
		assertEquals(initial + 2, sync().getHighestPatientSequence("testServer_1"));
		PatientIdentifier identifier = Context.getPatientService().getPatient(saved.getPatientId()).getPatientIdentifier();
		identifier.setIdentifier("UPD-DIRECT-" + UUID.randomUUID());
		Context.getPatientService().savePatientIdentifier(identifier);
		assertEquals(initial + 3, sync().getHighestPatientSequence("testServer_1"));
		assertEquals("Direccion directa", stored(saved).getPersonAddress().getAddress1());
	}
	
	@Test
	public void attributesAndIdentifierCorrectionsArriveWithoutChangingTheirUuids() {
		Patient source = sample();
		PersonAttributeType type = new PersonAttributeType();
		type.setName(prefix);
		type.setDescription("Test");
		type.setFormat("java.lang.String");
		Context.getPersonService().savePersonAttributeType(type);
		PersonAttribute attribute = new PersonAttribute(type, "111");
		source.addAttribute(attribute);
		create(source, "a");
		attribute.setValue("222");
		source.getPatientIdentifier().setIdentifier("UPD-CORRECT-" + UUID.randomUUID());
		receive().receivePatient(update(source, "a", 2, 10, "attributes", "identifiers"));
		Patient saved = stored(source);
		assertEquals(source.getPatientIdentifier().getUuid(), saved.getPatientIdentifier().getUuid());
		assertEquals(source.getPatientIdentifier().getIdentifier(), saved.getPatientIdentifier().getIdentifier());
		assertEquals("222", Context.getPersonService().getPersonAttributeByUuid(attribute.getUuid()).getValue());
	}
	
	@Test
	public void receivesWithTechnicalRoleHavingEditPermissions() throws Exception {
		technicalRole(true);
	}
	
	@Test
	public void refusesTechnicalRoleWithoutEditPermissions() throws Exception {
		technicalRole(false);
	}
	
	private void technicalRole(boolean edit) throws Exception {
		Patient source = sample(); create(source, "a");
		source.getPersonName().setGivenName("Correccion autorizada");
		String json = update(source, "a", 2, 10, "names");
		Role role = new Role(prefix);
		java.util.List<String> privileges = new ArrayList<>(Arrays.asList("Receive Synchronization Records", "View Synchronization Records",
		    "Get Patients", "Add Patients", "Get People", "Get Global Properties", "Get Patient Identifiers",
		    "Get Identifier Types", "Get Person Attribute Types", "Get Locations", "Get Concepts"));
		if (edit) { privileges.add("Edit Patients"); privileges.add("Edit People"); }
		for (String name : privileges) {
			Privilege privilege = Context.getUserService().getPrivilege(name);
			if (privilege == null) { privilege = Context.getUserService().savePrivilege(new Privilege(name, "Test")); }
			role.addPrivilege(privilege);
		}
		role = Context.getUserService().saveRole(role);
		User user = new User(); user.setPerson(Context.getPersonService().getPerson(2)); user.setUsername(prefix);
		user.addRole(role); Context.getUserService().createUser(user, "Test-Update123!");
		org.openmrs.api.context.Credentials admin = getCredentials();
		PatientReceiveService receiver = receive();
		Context.logout();
		try {
			Context.authenticate(new org.openmrs.api.context.UsernamePasswordCredentials(prefix, "Test-Update123!"));
			if (edit) { assertEquals(2, receiver.receivePatient(json)); }
			else { assertThrows(APIAuthenticationException.class, () -> receiver.receivePatient(json)); }
		} finally { Context.logout(); Context.authenticate(admin); }
		assertEquals(edit ? 2 : 1, receive().getConfirmedPatientSequence(origin("a")));
		assertEquals(edit ? "Correccion autorizada" : "Prueba", stored(source).getPersonName().getGivenName());
	}
	
	@Test
	public void malformedUpdateAndChangedPatientUuidAreRejected() throws Exception {
		Patient source = sample(); create(source, "a");
		String valid = update(source, "a", 2, 10, "names");
		ObjectNode event = (ObjectNode) mapper.readTree(valid);
		event.putArray("changedGroups").add("names").add("names");
		assertThrows(APIException.class, () -> receive().receivePatient(event.toString()));
		event.putArray("changedGroups").add("names"); event.put("entitySequence", 2.5);
		assertThrows(APIException.class, () -> receive().receivePatient(event.toString()));
		assertEquals(1, receive().getConfirmedPatientSequence(origin("a")));
		Patient saved = stored(source); saved.setUuid(UUID.randomUUID().toString());
		assertThrows(APIException.class, () -> Context.getPatientService().savePatient(saved));
	}
	
	@Test
	public void scalarCorrectionAndLaterLocalEditHaveIncreasingVersions() {
		Patient source = sample();
		create(source, "a");
		source.setBirthdate(java.sql.Date.valueOf("1991-08-16"));
		source.setBirthdateEstimated(true);
		source.setGender("M");
		receive().receivePatient(update(source, "a", 2, 10, "demographics"));
		Patient saved = stored(source);
		assertEquals("1991-08-16", new java.text.SimpleDateFormat("yyyy-MM-dd").format(saved.getBirthdate()));
		assertEquals("M", saved.getGender());
		assertTrue(saved.getBirthdateEstimated());
		saved.setGender("F");
		Context.getPatientService().savePatient(saved);
		PatientUpdateEvent local = new PatientUpdateEvent(sync().getPatientEventsAfter("testServer_1", localBefore, 1)
		        .get(0).getPayloadJson());
		assertTrue(local.occurredAt.isAfter(BASE.plusSeconds(10)));
		assertEquals("F", stored(source).getGender());
	}
	
	@Test
	public void localEditAndItsCounterRollbackTogether() {
		Patient saved = Context.getPatientService().savePatient(sample());
		String uuid = saved.getUuid();
		long before = sync().getHighestPatientSequence("testServer_1");
		TestTransaction.flagForCommit();
		TestTransaction.end();
		TestTransaction.start();
		saved = Context.getPatientService().getPatientByUuid(uuid);
		saved.getPersonName().setGivenName("Temporal");
		Context.getPatientService().savePatient(saved);
		assertEquals(before + 1, sync().getHighestPatientSequence("testServer_1"));
		TestTransaction.flagForRollback();
		TestTransaction.end();
		TestTransaction.start();
		assertEquals(before, sync().getHighestPatientSequence("testServer_1"));
		assertEquals("Prueba", Context.getPatientService().getPatientByUuid(uuid).getPersonName().getGivenName());
	}
	
	@Test
	public void patientIdentifierLookupKeepsOriginalIdentityAfterAlternatingCreateAndUpdate() {
		Patient first = Context.getPatientService().savePatient(sample());
		long createSequence = sync().getByPatientUuid(first.getUuid()).getSequence();
		first.setGender("M");
		Context.getPatientService().savePatient(first);
		Patient second = Context.getPatientService().savePatient(sample());
		List<PatientSyncEvent> page = sync().getPatientEventsAfter("testServer_1", createSequence - 1, 3);
		assertEquals(3, page.size());
		assertEquals(createSequence + 1, page.get(1).getSequence());
		assertEquals(createSequence + 2, sync().getByPatientUuid(second.getUuid()).getSequence());
		assertEquals(createSequence, sync().getByPatientUuid(first.getUuid()).getSequence());
	}
	
	@Test
	public void concurrentRemoteUpdatesConvergeWithoutDuplicateLocalEvents() throws Exception {
		Patient source = sample(); create(source, "creator");
		source.getPersonName().setGivenName("A"); final String a = update(source, "a", 1, 10, "names");
		source.getPersonName().setGivenName("B"); final String b = update(source, "b", 1, 20, "names");
		org.openmrs.api.context.Credentials credentials = getCredentials();
		TestTransaction.flagForCommit(); TestTransaction.end();
		java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
		java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
		try {
			List<java.util.concurrent.Future<Long>> results = new ArrayList<>();
			for (String json : Arrays.asList(a, b)) {
				results.add(pool.submit(() -> {
					Context.openSession();
					try { Context.authenticate(credentials); start.await(); return receive().receivePatient(json); }
					finally { Context.closeSession(); }
				}));
			}
			start.countDown();
			for (java.util.concurrent.Future<Long> result : results) { assertEquals(1L, result.get(30, java.util.concurrent.TimeUnit.SECONDS).longValue()); }
		} finally { pool.shutdownNow(); TestTransaction.start(); }
		assertEquals("B", stored(source).getPersonName().getGivenName());
		assertEquals(localBefore, sync().getHighestPatientSequence("testServer_1"));
		assertEquals(1, receive().getConfirmedPatientSequence(origin("a")));
		assertEquals(1, receive().getConfirmedPatientSequence(origin("b")));
	}
}
