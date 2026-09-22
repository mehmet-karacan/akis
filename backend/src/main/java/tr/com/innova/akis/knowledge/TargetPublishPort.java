package tr.com.innova.akis.knowledge;

/**
 * Technology port for the IKM publish step: apply the sealed work table to the target inside the target's atomic
 * publication boundary (fence, ledger intent, write, ledger record). Oracle publishes through {@code StagedPublishFacade};
 * PostgreSQL will do the same in one transaction.
 */
@FunctionalInterface
public interface TargetPublishPort {
    StagedKmRuntime.PublishResult publish(WorkTableManagerPort.Created object, JdbcStagingTransfer.Result seal);
}
