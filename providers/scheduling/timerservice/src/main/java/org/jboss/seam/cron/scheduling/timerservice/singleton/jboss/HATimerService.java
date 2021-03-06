/**
 * JBoss, Home of Professional Open Source Copyright 2011, Red Hat, Inc. and/or its affiliates, and individual contributors by the @authors
 * tag. See the copyright.txt in the distribution for a full listing of individual contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the License. You may
 * obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0 Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under the License.
 */
package org.jboss.seam.cron.scheduling.timerservice.singleton.jboss;

import java.util.Date;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.naming.InitialContext;
import javax.naming.NamingException;

import org.jboss.logging.Logger;
import org.jboss.msc.service.Service;
import org.jboss.msc.service.ServiceName;
import org.jboss.msc.service.StartContext;
import org.jboss.msc.service.StartException;
import org.jboss.msc.service.StopContext;
import org.jboss.seam.cron.util.PropertyResolver;

/**
 * <p>
 * A service to start schedule-timer as HASingleton timer in a clustered environment. The {@link HATimerServiceActivator} will ensure that
 * the timer is initialized only once in a cluster.</p>
 * <p>
 * The initialized timers must not persistent because it will be automatically restarted in case of a server restart and exists twice within
 * the cluster.<br/>
 * As this approach is no designed to interact with remote clients it is not possible to trigger reconfigurations. For this purpose it might
 * be a solution to read the timer configuration from a datasource and create a scheduler which checks this configuration and trigger the
 * reconfiguration.
 * </p>
 *
 * @author <a href="mailto:wfink@redhat.com">Wolf-Dieter Fink</a>
 */
public class HATimerService implements Service<String> {

    private static final Logger LOGGER = Logger.getLogger(HATimerService.class);
    private static final String deployModuleName = PropertyResolver.resolve("ha.singleton.module.name", true);
    public static final ServiceName SINGLETON_SERVICE_NAME = ServiceName.of(deployModuleName, "ha", "singleton", "timer");
    private static final int MAX_WAIT = 40000;
    private static final int WAIT_PART = 2000;
    
    /**
     * A flag whether the service is started.
     */
    private final AtomicBoolean started = new AtomicBoolean(false);

    /**
     * @return the name of the server node
     */
    public String getValue() throws IllegalStateException, IllegalArgumentException {
        LOGGER.infof("%s is %s at %s", HATimerService.class.getSimpleName(), (started.get() ? "started" : "not started"), System.getProperty("jboss.node.name"));
        return "";
    }

    public void start(StartContext arg0) throws StartException {
        if (!started.compareAndSet(false, true)) {
            throw new StartException("The service is still started!");
        }
        LOGGER.info("Start HASingleton timer service '" + this.getClass().getName() + "'");
        final String node = System.getProperty("jboss.node.name");
        // make several attempts to start the bean, understanding that it may not be ready to go yet if we're still in the deployment phase
        int waitedSoFar = 0;
        boolean initialized = false;
        int attempts = 0;
        Exception exampleException = null;
        while (!initialized && waitedSoFar < MAX_WAIT) {
            attempts ++;
            try {
                InitialContext ic = new InitialContext();
                ((Scheduler) ic.lookup("global/" + deployModuleName + "/SchedulerBean!org.jboss.seam.cron.scheduling.timerservice.singleton.jboss.Scheduler")).initialize("HASingleton timer @" + node + " " + new Date());
                initialized = true;
            } catch (NamingException e) {
                exampleException = e;
                LOGGER.info("Scheduler bean not found, so far we have waited " + waitedSoFar + " out of " + MAX_WAIT + ". May retry.");
            }
            if (!initialized) {
                try {
                    waitedSoFar += WAIT_PART;
                    Thread.sleep(WAIT_PART);
                } catch (Exception e) {
                    throw new StartException("Woken while waiting for scheduling bean to come onnline", e);
                }
            }
        }
        // .. but if we couldn't get it going after MAX_WAIT millis, there must be a problem
        if (!initialized) {
            throw new StartException("Could not initialize timer in " + deployModuleName + " after " + attempts 
                    + " over " + MAX_WAIT + " milliseconds", exampleException);
        }
    }

    public void stop(StopContext arg0) {
        if (!started.compareAndSet(true, false)) {
            LOGGER.warn("The service '" + this.getClass().getName() + "' is not active!");
        } else {
            LOGGER.info("Stop HASingleton timer service '" + this.getClass().getName() + "'");
            try {
                InitialContext ic = new InitialContext();
                ((Scheduler) ic.lookup("global/" + deployModuleName + "/SchedulerBean!org.jboss.seam.cron.scheduling.timerservice.singleton.jboss.Scheduler")).stop();
            } catch (NamingException e) {
                LOGGER.error("Could not stop timer", e);
            }
        }
    }
}
