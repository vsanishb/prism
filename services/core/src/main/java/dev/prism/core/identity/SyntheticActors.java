package dev.prism.core.identity;

/** Lets local seeders act as a synthetic staff member, so that seeded data takes the normal paths. */
public interface SyntheticActors {

    void runAs(String email, Runnable action);
}
