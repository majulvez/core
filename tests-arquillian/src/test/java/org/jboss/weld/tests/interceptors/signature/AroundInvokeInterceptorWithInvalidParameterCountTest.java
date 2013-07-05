/*
 * JBoss, Home of Professional Open Source
 * Copyright 2013, Red Hat, Inc., and individual contributors
 * by the @authors tag. See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jboss.weld.tests.interceptors.signature;

import javax.interceptor.AroundInvoke;
import javax.interceptor.Interceptor;
import javax.interceptor.InvocationContext;

import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.container.test.api.ShouldThrowException;
import org.jboss.arquillian.junit.Arquillian;
import org.jboss.shrinkwrap.api.Archive;
import org.jboss.shrinkwrap.api.BeanArchive;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * @author <a href="mailto:mluksa@redhat.com">Marko Luksa</a>
 */
@RunWith(Arquillian.class)
public class AroundInvokeInterceptorWithInvalidParameterCountTest {

    @Deployment
    // Can either be IllegalArgumentException (thrown by org.jboss.as.ee) or DefinitionException (thrown by Weld)
    @ShouldThrowException(Exception.class)
    public static Archive<?> deploy() {
        return ShrinkWrap.create(BeanArchive.class)
                .intercept(MyInterceptor.class)
                .addClasses(Intercept.class, InterceptedBean.class);
    }

    @Test
    public void testDeploymentFails() {
    }

    @Intercept
    @Interceptor
    public static class MyInterceptor {
        @AroundInvoke
        public Object aroundInvoke(InvocationContext ctx, String someOtherParam) throws Exception {
            return ctx.proceed();
        }
    }
}
