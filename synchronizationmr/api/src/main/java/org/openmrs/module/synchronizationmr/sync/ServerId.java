package org.openmrs.module.synchronizationmr.sync;

/** Identificador explícito del establecimiento; no genera ni transforma identidades. */
public final class ServerId {
	
	public static final String PROPERTY = "server.id";
	
	private ServerId() {
	}
	
	public static boolean isValid(String value) {
		return value != null && value.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,99}");
	}
	
	public static String requireValid(String value) {
		if (!isValid(value)) {
			throw new IllegalArgumentException("server.id debe contener de 1 a 100 letras ASCII, números, puntos, "
			        + "guiones o guiones bajos, empezando por letra o número, sin espacios");
		}
		return value;
	}
}
