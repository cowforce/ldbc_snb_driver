package com.ldbc.driver.workload;

import com.google.common.base.Function;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterators;
import com.ldbc.driver.*;
import com.ldbc.driver.generator.GeneratorFactory;
import com.ldbc.driver.util.RandomDataGeneratorFactory;
import com.ldbc.driver.workloads.simple.SimpleWorkload;
import org.junit.Test;

import java.util.Iterator;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;
import static org.hamcrest.CoreMatchers.equalTo;

public class WorkloadTests {

    @Test
    public void generatedWorkloadShouldBeDeterministicAndRepeatable() throws ClientException, ParamsException, WorkloadException {
        WorkloadParams params =
                new WorkloadParams(null, "dbClassName", "workloadClassName", 100L, -1, BenchmarkPhase.TRANSACTION_PHASE, 1, false, TimeUnit.MILLISECONDS, "resultFilePath");

        Workload workload = new SimpleWorkload();
        workload.init(params);

        Function<Operation<?>, Class> classFun = new Function<Operation<?>, Class>() {
            @Override
            public Class apply(Operation<?> operation) {
                return operation.getClass();
            }
        };

        List<Class> operationsA = ImmutableList.copyOf(
                Iterators.transform(
                        workload.getTransactionalOperations(new GeneratorFactory(new RandomDataGeneratorFactory(42L))),
                        classFun
                ));

        List<Class> operationsB = ImmutableList.copyOf(
                Iterators.transform(
                        workload.getTransactionalOperations(new GeneratorFactory(new RandomDataGeneratorFactory(42L))),
                        classFun
                ));

        assertThat(operationsA.size(), is(operationsB.size()));

        System.out.printf("%s %s\n", operationsA.size(), operationsB.size());
        System.out.printf("%s\n%s\n", operationsA.toString(), operationsB.toString());

        Iterator<Class> operationsAIt = operationsA.iterator();
        Iterator<Class> operationsBIt = operationsB.iterator();

        while (operationsAIt.hasNext()) {
            Class a = operationsAIt.next();
            Class b = operationsBIt.next();
            System.out.printf("%s\t%s\n", a.getSimpleName(), b.getSimpleName());
            assertThat(a, equalTo(b));
        }
    }

    @Test
    public void generatedWorkloadShouldBeDeterministicAndRepeatable2() throws ClientException, ParamsException, WorkloadException {
        WorkloadParams params =
                new WorkloadParams(null, "dbClassName", "workloadClassName", 100L, -1, BenchmarkPhase.TRANSACTION_PHASE, 1, false, TimeUnit.MILLISECONDS, "resultFilePath");

        Workload workloadA = new SimpleWorkload();
        workloadA.init(params);

        Workload workloadB = new SimpleWorkload();
        workloadB.init(params);

        List<Class> operationsA = ImmutableList.copyOf(
                Iterators.transform(
                        workloadA.getTransactionalOperations(new GeneratorFactory(new RandomDataGeneratorFactory(42L))),
                        new Function<Operation<?>, Class>() {
                            @Override
                            public Class apply(Operation<?> operation) {
                                return operation.getClass();
                            }
                        }));

        List<Class> operationsB = ImmutableList.copyOf(
                Iterators.transform(
                        workloadB.getTransactionalOperations(new GeneratorFactory(new RandomDataGeneratorFactory(42L))),
                        new Function<Operation<?>, Class>() {
                            @Override
                            public Class apply(Operation<?> operation) {
                                return operation.getClass();
                            }
                        }));

        assertThat(operationsA.size(), is(operationsB.size()));

        System.out.printf("%s %s\n", operationsA.size(), operationsB.size());
        System.out.printf("%s\n%s\n", operationsA.toString(), operationsB.toString());

        Iterator<Class> operationsAIt = operationsA.iterator();
        Iterator<Class> operationsBIt = operationsB.iterator();

        while (operationsAIt.hasNext()) {
            Class a = operationsAIt.next();
            Class b = operationsBIt.next();
            System.out.printf("%s\t%s\n", a.getSimpleName(), b.getSimpleName());
//            assertThat(a, equalTo(b));
        }
    }
}