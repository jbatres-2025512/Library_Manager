package org.jbatres.library_manager_api.entity;

/** Account status of a user. A SANCIONADO reader cannot borrow books. */
public enum UserStatus {
    ACTIVO,
    SANCIONADO
}