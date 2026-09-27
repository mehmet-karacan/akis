package tr.com.innova.akis.execution;

import tr.com.innova.akis.execution.ExecutionModels.Actor;

/** Test-only resolver for integration fixtures that use an explicit local user id. */
final class TestRunActorResolver extends RunActorResolver {

    private final ExecutionStore store;
    private final long actorId;

    TestRunActorResolver(ExecutionStore store, long actorId) {
        super(store);
        this.store = store;
        this.actorId = actorId;
    }

    @Override
    Actor currentActor() {
        return store.findActiveActor(actorId).orElseThrow();
    }
}
