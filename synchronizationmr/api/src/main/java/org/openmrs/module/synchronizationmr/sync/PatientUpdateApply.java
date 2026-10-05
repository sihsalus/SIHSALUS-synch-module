package org.openmrs.module.synchronizationmr.sync;

import java.util.*;
import org.openmrs.*;
import org.openmrs.api.context.Context;
import org.springframework.beans.BeanWrapper;
import org.springframework.beans.BeanWrapperImpl;

/**
 * Aplica solo los grupos seleccionados; los registros asociados anteriores omitidos se anulan, no
 * se eliminan.
 */
public final class PatientUpdateApply {
	
	private PatientUpdateApply() {
	}
	
	public static void apply(Patient target, Patient values, Set<String> groups) {
        if (groups.contains("demographics")) {
            copy(target, values, "gender,birthdate,birthtime,birthdateEstimated,dead,deathDate,deathdateEstimated,causeOfDeath,causeOfDeathNonCoded");
        }
        if (groups.contains("names")) {
            merge(target.getNames(), values.getNames(),
                "preferred,prefix,givenName,middleName,familyNamePrefix,familyName,familyName2,familyNameSuffix,degree",
                name -> name.setPerson(target));
        }
        if (groups.contains("addresses")) {
            merge(target.getAddresses(), values.getAddresses(),
                "preferred,address1,address2,address3,address4,address5,address6,address7,address8,address9,address10,address11,address12,address13,address14,address15,cityVillage,countyDistrict,stateProvince,country,postalCode,latitude,longitude,startDate,endDate",
                address -> {
                    address.setPerson(target);
                    // Asigna el ID local antes de insertar en la colección ordenada de Hibernate,
                    // para conservar direcciones distintas con igual contenido y preferencia.
                    Context.getPersonService().savePersonAddress(address);
                });
        }
        if (groups.contains("identifiers")) {
            merge(target.getIdentifiers(), values.getIdentifiers(), "identifier,preferred,identifierType,location",
                identifier -> identifier.setPatient(target));
        }
        if (groups.contains("attributes")) {
            merge(target.getAttributes(), values.getAttributes(), "attributeType,value", attribute -> attribute.setPerson(target));
        }
    }
	
	private static void copy(Object target, Object source, String properties) {
		BeanWrapper destination = new BeanWrapperImpl(target), incoming = new BeanWrapperImpl(source);
		// Lista fija de propiedades permitidas; sus nombres nunca se obtienen del JSON recibido.
		for (String property : properties.split(",")) {
			destination.setPropertyValue(property, incoming.getPropertyValue(property));
		}
	}
	
	private static <T extends OpenmrsData> void merge(Set<T> target, Set<T> incoming, String properties,
            java.util.function.Consumer<T> attach) {
        Map<String, T> existing = new HashMap<>();
        for (T item : target) { existing.put(item.getUuid(), item); }
        Set<String> present = new HashSet<>();
        for (T value : incoming) {
            present.add(value.getUuid());
            T current = existing.get(value.getUuid());
            if (current == null) {
                attach.accept(value);
                target.add(value);
            } else {
                copy(current, value, properties);
                current.setVoided(false); current.setVoidReason(null);
                current.setVoidedBy(null); current.setDateVoided(null);
            }
        }
        for (T item : target) {
            if (!present.contains(item.getUuid()) && !Boolean.TRUE.equals(item.getVoided())) {
                item.setVoided(true); item.setVoidReason("Synchronization: replaced by patient update");
                item.setVoidedBy(Context.getAuthenticatedUser()); item.setDateVoided(new Date());
            }
        }
    }
}
