package com.ldbc.driver.validation;

import com.ldbc.driver.*;
import com.ldbc.driver.runtime.ConcurrentErrorReporter;

import java.text.DecimalFormat;
import java.util.Iterator;

public class DbValidator {
    public DbValidationResult validate(Iterator<ValidationParam> validationParameters,
                                       Db db,
                                       int validationParamsCount) throws WorkloadException {
        System.out.println("----");
        DecimalFormat numberFormat = new DecimalFormat("###,###,###,###,###");
        DbValidationResult dbValidationResult = new DbValidationResult(db);
        ResultReporter resultReporter = new ResultReporter.SimpleResultReporter();

        int validationParamsProcessedSoFar = 0;
        int validationParamsCrashedSoFar = 0;
        int validationParamsIncorrectSoFar = 0;

        while (validationParameters.hasNext()) {
            ValidationParam validationParam = validationParameters.next();
            Operation operation = validationParam.operation();
            Object expectedOperationResult = validationParam.operationResult();

            OperationHandlerRunnableContext handlerRunner;
            try {
                handlerRunner = db.getOperationHandlerRunnableContext(operation);
            } catch (Throwable e) {
                // Not necessary, but perhaps useful for debugging
                e.printStackTrace();
                dbValidationResult.reportMissingHandlerForOperation(operation);
                // TODO remove
                System.out.println(String.format("HANDLER: %s %s %s", operation.getClass().getSimpleName(), expectedOperationResult.getClass().getSimpleName()));
                continue;
            }

            try {
                OperationHandler handler = handlerRunner.operationHandler();
                DbConnectionState dbConnectionState = handlerRunner.dbConnectionState();
                System.out.print(String.format("Processed %s / %s -- Crashed %s -- Incorrect %s -- Currently processing %s...\r",
                        numberFormat.format(validationParamsProcessedSoFar),
                        numberFormat.format(validationParamsCount),
                        numberFormat.format(validationParamsCrashedSoFar),
                        numberFormat.format(validationParamsIncorrectSoFar),
                        operation.getClass().getSimpleName()
                ));
                handler.executeOperation(operation, dbConnectionState, resultReporter);
            } catch (Throwable e) {
                // Not necessary, but perhaps useful for debugging
                e.printStackTrace();
                validationParamsCrashedSoFar++;
                dbValidationResult.reportUnableToExecuteOperation(operation, ConcurrentErrorReporter.stackTraceToString(e));
                // TODO remove
                System.out.println(String.format("CRASHED: %s %s %s", operation.getClass().getSimpleName(), expectedOperationResult.getClass().getSimpleName()));
                continue;
            } finally {
                validationParamsProcessedSoFar++;
                handlerRunner.cleanup();
            }

            Object actualOperationResult = resultReporter.result();

            if (false == expectedOperationResult.equals(actualOperationResult)) {
                validationParamsIncorrectSoFar++;
                // TODO remove
                System.out.println(String.format("INCORRECT: %s %s %s", operation.getClass().getSimpleName(), expectedOperationResult.getClass().getSimpleName(), actualOperationResult.getClass().getSimpleName()));
                dbValidationResult.reportIncorrectResultForOperation(operation, expectedOperationResult, actualOperationResult);
                continue;
            }

            dbValidationResult.reportSuccessfulExecution(operation);
        }
        System.out.println("----");
        return dbValidationResult;
    }
}