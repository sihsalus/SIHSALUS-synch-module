package org.openmrs.module.synchronizationmr.sync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;
import org.openmrs.*;
import org.openmrs.api.context.Context;
import static org.openmrs.module.synchronizationmr.sync.EventJson.*;

/** Estado clínico-administrativo de la visita; no incluye facturas ni cobros. */
public final class VisitMetadata {
	
	public static final String TEXT = "org.openmrs.customdatatype.datatype.FreeTextDatatype";
	
	public static final String CONCEPT = "org.openmrs.customdatatype.datatype.ConceptDatatype";
	
	private VisitMetadata() {
	}
	
	public static boolean supported(String datatype) {
		return TEXT.equals(datatype) || CONCEPT.equals(datatype);
	}
	
	public static ObjectNode snapshot(Visit visit) {
		return fromVisitSnapshot(EncounterVisitSnapshot.serialize(visit));
	}
	
	/** Solo los atributos activos determinan el estado vigente; el historial nativo se conserva. */
	public static ObjectNode fromVisitSnapshot(JsonNode visit) {
        if (visit.path("unsupportedAttributes").asBoolean()) throw invalid();
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        for (String key : Arrays.asList("visitTypeUuid", "locationUuid", "indicationUuid")) {
            result.set(key, visit.has(key) ? visit.get(key) : NullNode.instance);
        }
        ArrayNode attrs = result.putArray("attributes");
        List<JsonNode> active = new ArrayList<>();
        for (JsonNode a : visit.path("attributes")) {
            if (!a.path("voided").asBoolean()) active.add(a);
        }
        active.sort(Comparator.comparing(a -> a.path("uuid").asText()));
        for (JsonNode a : active) {
            ObjectNode item = ((ObjectNode)a).deepCopy();
            item.remove(Arrays.asList("voided", "voidReason"));
            attrs.add(item);
        }
        validate(result);
        return result;
    }
	
	public static void validate(JsonNode data) {
        fields(data, "visitTypeUuid,locationUuid,indicationUuid,attributes");
        reference(text(data,"visitTypeUuid",true));
        for (String key : Arrays.asList("locationUuid","indicationUuid")) {
            if (!data.has(key)) throw invalid();
            String value=text(data,key,false); if(value!=null) reference(value);
        }
        Set<String> ids=new HashSet<>();
        for(JsonNode a:array(data,"attributes",false)) {
            fields(a,"uuid,typeUuid,datatype,datatypeConfig,value");
            unique(a,ids);reference(text(a,"typeUuid",true));
            String datatype=text(a,"datatype",true);
            if(!supported(datatype) || !a.has("datatypeConfig")) throw invalid();
            text(a,"datatypeConfig",false);
            String value=text(a,"value",true);
            if(value.length()>65535) throw invalid();
            if(CONCEPT.equals(datatype)) reference(value);
        }
    }
	
	public static void validateConcept(String datatype, String value) {
		if (CONCEPT.equals(datatype)) {
			Concept concept = Context.getConceptService().getConceptByUuid(reference(value));
			if (concept == null || concept.getRetired())
				throw dependency("visit.attribute.value");
		}
	}
	
	public static void apply(Visit target, JsonNode data, Date eventDate) {
        validate(data);
        VisitType type=Context.getVisitService().getVisitTypeByUuid(text(data,"visitTypeUuid",true));
        if(type==null || type.getRetired()) throw dependency("visitTypeUuid");
        String loc=text(data,"locationUuid",false), indication=text(data,"indicationUuid",false);
        Location location=loc==null?null:Context.getLocationService().getLocationByUuid(loc);
        Concept concept=indication==null?null:Context.getConceptService().getConceptByUuid(indication);
        if(loc!=null && (location==null || location.getRetired())) throw dependency("visit.locationUuid");
        if(indication!=null && (concept==null || concept.getRetired())) throw dependency("visit.indicationUuid");
        List<VisitAttribute> incoming=new ArrayList<>();
        Map<String,VisitAttribute> existing=new HashMap<>();
        for(VisitAttribute a:target.getAttributes()) existing.put(a.getUuid(),a);
        for(JsonNode a:data.path("attributes")) {
            VisitAttributeType at=Context.getVisitService().getVisitAttributeTypeByUuid(text(a,"typeUuid",true));
            if(at==null || at.getRetired()) throw dependency("visit.attribute.typeUuid");
            if(!Objects.equals(at.getDatatypeClassname(),text(a,"datatype",true))
                || !Objects.equals(at.getDatatypeConfig(),text(a,"datatypeConfig",false))) throw invalid();
            String value=text(a,"value",true);
            validateConcept(at.getDatatypeClassname(),value);
            VisitAttribute attr=existing.get(text(a,"uuid",true));
            if(attr==null) {
                if(Context.getVisitService().getVisitAttributeByUuid(text(a,"uuid",true))!=null) throw conflict();
                attr=new VisitAttribute();attr.setUuid(text(a,"uuid",true));
                attr.setCreator(Context.getAuthenticatedUser());attr.setDateCreated(eventDate);
                attr.setAttributeType(at);
            } else if(!attr.getAttributeType().getUuid().equals(at.getUuid())) throw conflict();
            attr.setValueReferenceInternal(value);
            attr.setVoided(target.getVoided());
            attr.setVoidReason(target.getVoided()?target.getVoidReason():null);
            attr.setVoidedBy(target.getVoided()?Context.getAuthenticatedUser():null);
            attr.setDateVoided(target.getVoided()?target.getDateVoided():null);
            incoming.add(attr);
        }
        // Los atributos sustituidos se anulan, nunca se borran físicamente.
        Set<String> retained=new HashSet<>();
        for(VisitAttribute a:incoming) retained.add(a.getUuid());
        for(VisitAttribute a:target.getAttributes()) {
            if(!a.getVoided() && !retained.contains(a.getUuid())) {
                a.setVoided(true);a.setVoidReason("Sustituido por sincronización de visita");
                a.setVoidedBy(Context.getAuthenticatedUser());a.setDateVoided(eventDate);
            }
        }
        for(VisitAttribute a:incoming) if(!existing.containsKey(a.getUuid())) target.addAttribute(a);
        target.setVisitType(type);target.setLocation(location);target.setIndication(concept);
    }
}
