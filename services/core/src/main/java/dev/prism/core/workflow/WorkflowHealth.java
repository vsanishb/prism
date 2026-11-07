package dev.prism.core.workflow;

/** Reports whether the workflow engine can be reached, for the system status screen. */
public interface WorkflowHealth {

    boolean reachable();
}
