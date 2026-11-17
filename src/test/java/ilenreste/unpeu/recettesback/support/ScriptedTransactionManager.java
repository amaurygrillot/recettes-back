package ilenreste.unpeu.recettesback.support;

import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

/**
 * A <strong>real</strong> Spring transaction manager with nothing behind it, whose begin, commit and rollback can each
 * be made to fail on cue. It is {@link AbstractPlatformTransactionManager}, the class whose {@code processCommit} and
 * {@code processRollback} decide which {@code afterCompletion} status every synchronization is told, so a test over it
 * sees what Spring itself reports (a commit that fails with a {@code DataAccessException} is « rolled back »; one that
 * fails with a {@code TransactionException} is « unknown »), not what the test supposes it reports.
 * <p>
 * For the units that reason about how a transaction ended and have no database to ask: a failing commit is what a
 * connection that drops at the database looks like from this side, and a real database cannot be made to do that on
 * cue in a unit test. (The integration test of the same rule terminates a real PostgreSQL backend instead.)
 */
public class ScriptedTransactionManager extends AbstractPlatformTransactionManager {

    private RuntimeException beginFailure;
    private RuntimeException commitFailure;
    private RuntimeException rollbackFailure;
    private int commits;
    private int rollbacks;

    /** The next transactions cannot begin (no connection is available, say). */
    public ScriptedTransactionManager failingToBeginWith(RuntimeException failure) {
        beginFailure = failure;
        return this;
    }

    /** The next commits fail, as a connection that drops while the database commits does. */
    public ScriptedTransactionManager failingToCommitWith(RuntimeException failure) {
        commitFailure = failure;
        return this;
    }

    /** The next rollbacks fail, as one over a connection that has already died does. */
    public ScriptedTransactionManager failingToRollbackWith(RuntimeException failure) {
        rollbackFailure = failure;
        return this;
    }

    public int commits() {
        return commits;
    }

    public int rollbacks() {
        return rollbacks;
    }

    @Override
    protected Object doGetTransaction() {
        return new Object();
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
        if (beginFailure != null) {
            throw beginFailure;
        }
    }

    @Override
    protected void doCommit(DefaultTransactionStatus status) {
        if (commitFailure != null) {
            throw commitFailure;
        }
        commits++;
    }

    @Override
    protected void doRollback(DefaultTransactionStatus status) {
        if (rollbackFailure != null) {
            throw rollbackFailure;
        }
        rollbacks++;
    }
}
