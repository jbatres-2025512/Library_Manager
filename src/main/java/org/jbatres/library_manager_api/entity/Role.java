package org.jbatres.library_manager_api.entity;

/**
 * System roles. Values are intentionally aligned with the evaluation document
 * (ADMIN, BIBLIOTECARIO, LECTOR) because they are persisted and exposed in JSON.
 */
public enum Role {
    ADMIN,
    BIBLIOTECARIO,
    LECTOR
}