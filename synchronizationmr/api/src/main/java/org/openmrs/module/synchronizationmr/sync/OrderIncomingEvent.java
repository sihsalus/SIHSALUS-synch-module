package org.openmrs.module.synchronizationmr.sync;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import java.util.*;
import java.util.function.Function;
import java.sql.*;
import org.openmrs.*;
import org.openmrs.api.APIException;
import org.openmrs.api.context.Context;
import static org.openmrs.module.synchronizationmr.sync.EventJson.*;
import static org.openmrs.module.synchronizationmr.sync.OrderPayload.*;

public final class OrderIncomingEvent {
	
	private static final ObjectMapper MAPPER = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
	        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
	
	public final JsonNode root;
	
	public final String json, origin, eventUuid, orderUuid, patientUuid;
	
	public final long sequence;
	
	public final java.util.Date occurredAt;
	
	public OrderIncomingEvent(String json) {
		if (json == null || json.length() > 1000000)
			throw invalid();
		try {
			root = MAPPER.readTree(json);
		}
		catch (Exception e) {
			throw invalid();
		}
		fields(root, "schemaVersion,eventUuid,originServerId,entityType,entitySequence,operation,occurredAt,payload");
		JsonNode v = root.get("schemaVersion"), n = root.get("entitySequence");
		if (v == null || !v.isIntegralNumber() || !v.canConvertToInt() || v.intValue() != 1
		        || !"ORDER".equals(text(root, "entityType", true)) || !"CREATE".equals(text(root, "operation", true))
		        || n == null || !n.isIntegralNumber() || !n.canConvertToLong() || n.longValue() < 1)
			throw invalid();
		sequence = n.longValue();
		origin = ServerId.requireValid(text(root, "originServerId", true));
		eventUuid = uuid(text(root, "eventUuid", true));
		occurredAt = instant(text(root, "occurredAt", true));
		JsonNode data = root.get("payload");
		String kind = text(data, "kind", true);
		String specific = "DRUG".equals(kind) ? DRUG + "," + DRUG_REFS : "TEST".equals(kind) ? TEST + "," + TEST_REFS : "";
		if (!"DRUG".equals(kind) && !"TEST".equals(kind) && !"ORDER".equals(kind))
			throw invalid();
		fields(data, "kind,orderUuid,patientUuid,encounterUuid,sourceOrderNumber,dateStopped,previousOrderDateStopped,"
		        + COMMON + "," + REFS + "," + specific);
		orderUuid = reference(text(data, "orderUuid", true));
		patientUuid = reference(text(data, "patientUuid", true));
		this.json = json;
	}
	
	public boolean sameContent(String other) {
		try {
			return other != null && root.equals(MAPPER.readTree(other));
		}
		catch (Exception e) {
			return false;
		}
		
	}
	
	public Order toOrder() {
        JsonNode d=root.get("payload"); String kind=text(d,"kind",true);
        Order o="DRUG".equals(kind) ? new DrugOrder() : "TEST".equals(kind) ? new TestOrder() : new Order();
        o.setUuid(orderUuid); read(d,o,COMMON);
        Patient patient=resolve(d,"patientUuid",true,Context.getPatientService()::getPatientByUuid);
        if (Boolean.TRUE.equals(patient.getVoided())) throw dependency("patientUuid");
        o.setPatient(patient);
        Encounter encounter=resolve(d,"encounterUuid",true,Context.getEncounterService()::getEncounterByUuid);
        if (Boolean.TRUE.equals(encounter.getVoided()) || !patientUuid.equals(encounter.getPatient().getUuid())) throw invalid();
        o.setEncounter(encounter);
        o.setOrderType(resolve(d,"orderTypeUuid",true,Context.getOrderService()::getOrderTypeByUuid));
        o.setConcept(resolve(d,"conceptUuid",true,Context.getConceptService()::getConceptByUuid));
        o.setOrderer(resolve(d,"ordererUuid",true,Context.getProviderService()::getProviderByUuid));
        o.setOrderReason(resolve(d,"orderReasonUuid",false,Context.getConceptService()::getConceptByUuid));
        o.setCareSetting(resolve(d,"careSettingUuid",true,Context.getOrderService()::getCareSettingByUuid));
        Order previous=resolve(d,"previousOrderUuid",false,Context.getOrderService()::getOrderByUuid);
        if (previous!=null && (!patientUuid.equals(previous.getPatient().getUuid()) || Boolean.TRUE.equals(previous.getVoided()))) throw invalid();
        o.setPreviousOrder(previous);
        if (o.getAction()!=Order.Action.NEW && previous==null) throw dependency("previousOrderUuid");
        if (text(d,"orderGroupUuid",false)!=null) {
            throw new APIException("Order groups require a separate synchronization contract");
        }
        if (o instanceof DrugOrder) {
            DrugOrder drug=(DrugOrder)o; read(d,drug,DRUG);
            drug.setDoseUnits(resolve(d,"doseUnitsUuid",false,Context.getConceptService()::getConceptByUuid));
            drug.setFrequency(resolve(d,"frequencyUuid",false,Context.getOrderService()::getOrderFrequencyByUuid));
            drug.setQuantityUnits(resolve(d,"quantityUnitsUuid",false,Context.getConceptService()::getConceptByUuid));
            drug.setDrug(resolve(d,"drugUuid",false,Context.getConceptService()::getDrugByUuid));
            drug.setDurationUnits(resolve(d,"durationUnitsUuid",false,Context.getConceptService()::getConceptByUuid));
            drug.setRoute(resolve(d,"routeUuid",false,Context.getConceptService()::getConceptByUuid));
            String dosing=text(d,"dosingType",false);
            if (SimpleDosingInstructions.class.getName().equals(dosing)) drug.setDosingType(SimpleDosingInstructions.class);
            else if (FreeTextDosingInstructions.class.getName().equals(dosing)) drug.setDosingType(FreeTextDosingInstructions.class);
            else if (dosing==null && o.getAction()==Order.Action.DISCONTINUE) drug.setDosingType(null);
            else throw new APIException("Unsupported dosing instructions class");
        } else if (o instanceof TestOrder) {
            TestOrder test=(TestOrder)o; read(d,test,TEST);
            test.setSpecimenSource(resolve(d,"specimenSourceUuid",false,Context.getConceptService()::getConceptByUuid));
            test.setFrequency(resolve(d,"frequencyUuid",false,Context.getOrderService()::getOrderFrequencyByUuid));
        }
        java.util.Date stopped=instant(text(d,"dateStopped",false));
        if (o.getDateActivated()==null || (stopped!=null && (stopped.before(o.getDateActivated()) || stopped.after(new java.util.Date())))) throw invalid();
        if (o.getAutoExpireDate()!=null && o.getAutoExpireDate().before(o.getDateActivated())) throw invalid();
        if (o.getAction()==Order.Action.DISCONTINUE && (stopped!=null || !o.getDateActivated().equals(o.getAutoExpireDate()))) throw invalid();
        if (o.getAction()==Order.Action.REVISE || o.getAction()==Order.Action.DISCONTINUE) {
            java.util.Date priorStop=instant(text(d,"previousOrderDateStopped",true));
            if (priorStop.before(previous.getDateActivated()) || priorStop.after(o.getDateActivated())) throw invalid();
        }
        return o;
    }
	
	public void preparePreviousSnapshot(Order incoming, Connection connection, org.hibernate.Session session) throws SQLException {
        Order previous=incoming.getPreviousOrder();
        if (previous==null || previous.getDateStopped()==null
            || (incoming.getAction()!=Order.Action.REVISE && incoming.getAction()!=Order.Action.DISCONTINUE)) return;
        // Only an imported, already-stopped snapshot belonging to this exact chain is eligible.
        java.util.Date sourceStop=instant(text(root.get("payload"),"previousOrderDateStopped",true));
        long expected=sourceStop.getTime();
        if (previous.getDateStopped().getTime()!=expected) throw new APIException("Previous order stop conflicts with this revision");
        try (PreparedStatement q=connection.prepareStatement("select payload_json from synchronizationmr_order_event where order_id = ?")) {
            q.setInt(1,previous.getOrderId());
            try (ResultSet r=q.executeQuery()) {
                if (!r.next() || r.getString(1)==null) throw new APIException("Previous order has no synchronized snapshot");
                OrderIncomingEvent prior=new OrderIncomingEvent(r.getString(1));
                java.util.Date recorded=instant(text(prior.root.get("payload"),"dateStopped",false));
                if (recorded==null || recorded.getTime()!=expected) throw new APIException("Previous order changed since its snapshot");
            }
        }
        session.flush();
        try (PreparedStatement s=connection.prepareStatement("update orders set date_stopped = null where order_id = ?")) {
            s.setInt(1,previous.getOrderId());s.executeUpdate();
        }
        session.refresh(previous);
        // saveRetrospectiveOrder reapplies this stop; any failure rolls the entire transaction back.
    }
	
	/**
	 * Native service allocates a destination order number. Source number stays in the immutable
	 * event. Restore snapshot timestamps after its normal save rules (which round expiry to end of
	 * day).
	 */
	public void restoreSnapshotDates(Order saved, Connection connection) throws SQLException {
        JsonNode d=root.get("payload");
        java.util.Date stopped=instant(text(d,"dateStopped",false)), expiry=instant(text(d,"autoExpireDate",false));
        try (PreparedStatement s=connection.prepareStatement("update orders set date_stopped = ?, auto_expire_date = ? where order_id = ?")) {
            s.setTimestamp(1,stopped==null?null:new Timestamp(stopped.getTime()));
            s.setTimestamp(2,expiry==null?null:new Timestamp(expiry.getTime())); s.setInt(3,saved.getOrderId()); s.executeUpdate();
        }
        if (saved.getPreviousOrder()!=null && (saved.getAction()==Order.Action.REVISE || saved.getAction()==Order.Action.DISCONTINUE)) {
            java.util.Date priorStop=instant(text(d,"previousOrderDateStopped",true));
            try (PreparedStatement q=connection.prepareStatement("update orders set date_stopped = ? where order_id = ?")) {
                q.setTimestamp(1,new Timestamp(priorStop.getTime()));q.setInt(2,saved.getPreviousOrder().getOrderId());q.executeUpdate();
            }
        }

    }
	
	private <T extends OpenmrsObject> T resolve(JsonNode d, String field, boolean required, Function<String, T> lookup) {
		String id = text(d, field, required);
		if (id == null)
			return null;
		T value = lookup.apply(reference(id));
		if (value == null
		        || (value instanceof OpenmrsMetadata && Boolean.TRUE.equals(((OpenmrsMetadata) value).getRetired()))
		        || (value instanceof Concept && Boolean.TRUE.equals(((Concept) value).getRetired())))
			throw dependency(field);
		return value;
	}
}
