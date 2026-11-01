package io.github.hectorvent.floci.services.appsync.graphql.datasource;

import io.github.hectorvent.floci.core.common.AwsException;

/**
 * A data source error that AppSync answers with a result beside it: the response handler sees both
 * {@code ctx.error} and {@code ctx.result}. A cancelled DynamoDB transaction is the case, its
 * {@code ctx.result.cancellationReasons} saying which item failed and why.
 */
public class AppSyncDataSourceException extends AwsException {

    private final transient Object result;

    public AppSyncDataSourceException(String errorCode, String message, int httpStatus, Object result) {
        super(errorCode, message, httpStatus);
        this.result = result;
    }

    /** What the response handler sees as {@code ctx.result} beside the error. */
    public Object result() {
        return result;
    }
}
