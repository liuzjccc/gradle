/*
 * Copyright 2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.configuration.internal;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableSet;
import groovy.lang.Closure;
import org.apache.commons.lang.ClassUtils;
import org.gradle.BuildListener;
import org.gradle.api.Action;
import org.gradle.api.ProjectEvaluationListener;
import org.gradle.api.execution.TaskExecutionGraphListener;
import org.gradle.configuration.internal.ExecuteListenerBuildOperationType.DetailsImpl;
import org.gradle.internal.InternalListener;
import org.gradle.internal.operations.BuildOperationContext;
import org.gradle.internal.operations.BuildOperationDescriptor;
import org.gradle.internal.operations.BuildOperationExecutor;
import org.gradle.internal.operations.RunnableBuildOperation;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.List;

import static org.gradle.configuration.internal.ExecuteListenerBuildOperationType.RESULT;

public class DefaultListenerBuildOperationDecorator implements ListenerBuildOperationDecorator {

    private static final ImmutableSet<Class<?>> SUPPORTED_INTERFACES = ImmutableSet.of(
        BuildListener.class,
        ProjectEvaluationListener.class,
        TaskExecutionGraphListener.class
    );

    // we don't decorate everything in BuildListener, just projectsLoaded/projectsEvaluated
    private static final ImmutableSet<String> UNDECORATED_METHOD_NAMES = ImmutableSet.of(
        "buildStarted",
        "settingsEvaluated",
        "buildFinished"
    );


    private final BuildOperationExecutor buildOperationExecutor;

    @VisibleForTesting
    final DefaultUserCodeApplicationContext userCodeApplicationContext = new DefaultUserCodeApplicationContext();

    public DefaultListenerBuildOperationDecorator(BuildOperationExecutor buildOperationExecutor) {
        this.buildOperationExecutor = buildOperationExecutor;
    }

    public UserCodeApplicationContext getUserCodeApplicationContext() {
        return userCodeApplicationContext;
    }

    public <T> Action<T> decorate(String name, Action<T> action) {
        UserCodeApplicationId applicationId = userCodeApplicationContext.current();
        if (applicationId == null || action instanceof InternalListener) {
            return action;
        }
        return new BuildOperationEmittingAction<T>(applicationId, name, action);
    }

    public <T> Closure<T> decorate(String name, Closure<T> closure) {
        UserCodeApplicationId applicationId = userCodeApplicationContext.current();
        if (applicationId == null) {
            return closure;
        }
        return new BuildOperationEmittingClosure<T>(applicationId, name, closure);
    }

    @SuppressWarnings("unchecked")
    public <T> T decorate(Class<T> targetClass, T listener) {
        if (listener instanceof InternalListener || !isSupported(listener)) {
            return listener;
        }

        UserCodeApplicationId applicationId = userCodeApplicationContext.current();
        if (applicationId == null) {
            return listener;
        }

        Class<?> listenerClass = listener.getClass();
        List<Class<?>> allInterfaces = ClassUtils.getAllInterfaces(listenerClass);
        BuildOperationEmittingInvocationHandler handler = new BuildOperationEmittingInvocationHandler(applicationId, listener);
        return targetClass.cast(Proxy.newProxyInstance(listenerClass.getClassLoader(), allInterfaces.toArray(new Class[0]), handler));
    }

    public Object decorateUnknownListener(Object listener) {
        return decorate(Object.class, listener);
    }

    private static boolean isSupported(Object listener) {
        for (Class<?> i : SUPPORTED_INTERFACES) {
            if (i.isInstance(listener)) {
                return true;
            }
        }
        return false;
    }

    private static abstract class Operation implements RunnableBuildOperation {

        private final UserCodeApplicationId applicationId;
        private final String name;

        protected Operation(UserCodeApplicationId applicationId, String name) {
            this.applicationId = applicationId;
            this.name = name;
        }

        @Override
        public BuildOperationDescriptor.Builder description() {
            return BuildOperationDescriptor
                .displayName("Execute " + name + " listener")
                .details(new DetailsImpl(applicationId));
        }
    }

    private class BuildOperationEmittingAction<T> implements Action<T> {

        private final UserCodeApplicationId applicationId;
        private final String name;
        private final Action<T> delegate;

        private BuildOperationEmittingAction(UserCodeApplicationId applicationId, String name, Action<T> delegate) {
            this.applicationId = applicationId;
            this.delegate = delegate;
            this.name = name;
        }

        @Override
        public void execute(final T arg) {
            buildOperationExecutor.run(new Operation(applicationId, name) {
                @Override
                public void run(final BuildOperationContext context) {
                    userCodeApplicationContext.reapply(applicationId, new Runnable() {
                        @Override
                        public void run() {
                            delegate.execute(arg);
                        }
                    });
                    context.setResult(RESULT);
                }
            });
        }
    }

    private class BuildOperationEmittingClosure<T> extends Closure<T> {

        private final UserCodeApplicationId applicationId;
        private final String name;
        private final Closure<T> delegate;

        private BuildOperationEmittingClosure(UserCodeApplicationId application, String name, Closure<T> delegate) {
            super(delegate.getOwner(), delegate.getThisObject());
            this.applicationId = application;
            this.delegate = delegate;
            this.name = name;
        }

        public void doCall(final Object... args) {
            buildOperationExecutor.run(new Operation(applicationId, name) {
                @Override
                public void run(final BuildOperationContext context) {
                    userCodeApplicationContext.reapply(applicationId, new Runnable() {
                        @Override
                        public void run() {
                            int numClosureArgs = delegate.getMaximumNumberOfParameters();
                            Object[] finalArgs = numClosureArgs < args.length ? Arrays.copyOf(args, numClosureArgs) : args;
                            delegate.call(finalArgs);
                            context.setResult(RESULT);
                        }
                    });
                }
            });
        }

        @Override
        public void setDelegate(Object delegateObject) {
            delegate.setDelegate(delegateObject);
        }

        @Override
        public void setResolveStrategy(int resolveStrategy) {
            delegate.setResolveStrategy(resolveStrategy);
        }

        @Override
        public int getMaximumNumberOfParameters() {
            return delegate.getMaximumNumberOfParameters();
        }
    }

    private class BuildOperationEmittingInvocationHandler implements InvocationHandler {

        private final UserCodeApplicationId applicationId;
        private final Object delegate;

        private BuildOperationEmittingInvocationHandler(UserCodeApplicationId applicationId, Object delegate) {
            this.applicationId = applicationId;
            this.delegate = delegate;
        }

        @Override
        public Object invoke(Object proxy, final Method method, final Object[] args) throws Throwable {
            final String methodName = method.getName();
            if (methodName.equals("toString") && (args == null || args.length == 0)) {
                return "BuildOperationEmittingBuildListenerInvocationHandler{delegate: " + delegate + "}";
            } else if (methodName.equals("hashCode") && (args == null || args.length == 0)) {
                return delegate.hashCode();
            } else if (methodName.equals("equals") && args.length == 1) {
                return proxy == args[0] || isSame(args[0]);
            } else if (!SUPPORTED_INTERFACES.contains(method.getDeclaringClass()) || UNDECORATED_METHOD_NAMES.contains(methodName)) {
                return method.invoke(delegate, args);
            } else {
                buildOperationExecutor.run(new Operation(applicationId, methodName) {
                    @Override
                    public void run(final BuildOperationContext context) {
                        userCodeApplicationContext.reapply(applicationId, new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    method.invoke(delegate, args);
                                    context.setResult(RESULT);
                                } catch (Exception e) {
                                    context.failed(e);
                                }
                            }
                        });
                    }
                });

                // all of the interfaces that we decorate have 100% void methods
                //noinspection ConstantConditions
                return null;
            }
        }

        private boolean isSame(Object arg) {
            if (Proxy.isProxyClass(arg.getClass())) {
                InvocationHandler invocationHandler = Proxy.getInvocationHandler(arg);
                if (getClass() == invocationHandler.getClass()) {
                    BuildOperationEmittingInvocationHandler cast = (BuildOperationEmittingInvocationHandler) invocationHandler;
                    return cast.delegate.equals(delegate);
                }
            }
            return false;
        }
    }


}
