package io.github.hectorvent.floci.services.cloudformation.model;

/**
 * The failure tolerance of a stack set operation's {@code OperationPreferences}: "The number of accounts per Region
 * this operation can fail in before CloudFormation stops the operation in that Region", given as a count or as a
 * percentage of the Region's accounts, which CloudFormation "rounds down to the next whole number"; with neither, the
 * tolerance is 0 (CloudFormation API Reference, {@code StackSetOperationPreferences}). An operation within its
 * tolerance in every Region is {@code SUCCEEDED}, and one past it in any Region {@code FAILED} (CloudFormation User
 * Guide, StackSets concepts).
 *
 * <p>The concurrency preferences are not kept: Floci deploys an operation's instances one after another, so how many
 * run at once changes nothing an operation's caller can read.
 */
public record StackSetOperationPreferences(Integer failureToleranceCount, Integer failureTolerancePercentage) {

    /** The preferences of an operation that names none: a tolerance of 0. */
    public static final StackSetOperationPreferences DEFAULT = new StackSetOperationPreferences(null, null);

    /** How many of a Region's {@code accounts} instances may fail before the operation fails there. */
    public int toleratedFailures(int accounts) {
        if (failureToleranceCount != null) {
            return failureToleranceCount;
        }
        if (failureTolerancePercentage != null) {
            return failureTolerancePercentage * accounts / 100;
        }
        return 0;
    }
}
