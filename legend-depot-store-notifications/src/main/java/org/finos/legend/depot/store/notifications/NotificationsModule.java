//  Copyright 2021 Goldman Sachs
//
//  Licensed under the Apache License, Version 2.0 (the "License");
//  you may not use this file except in compliance with the License.
//  You may obtain a copy of the License at
//
//       http://www.apache.org/licenses/LICENSE-2.0
//
//  Unless required by applicable law or agreed to in writing, software
//  distributed under the License is distributed on an "AS IS" BASIS,
//  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//  See the License for the specific language governing permissions and
//  limitations under the License.
//

package org.finos.legend.depot.store.notifications;

import com.google.inject.PrivateModule;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import org.finos.legend.depot.store.mongo.admin.MongoAdminStore;
import org.finos.legend.depot.store.notifications.api.Notifications;
import org.finos.legend.depot.store.notifications.api.NotificationsManager;
import org.finos.legend.depot.store.notifications.services.NotificationsQueueManager;
import org.finos.legend.depot.store.notifications.store.api.NotificationsStore;
import org.finos.legend.depot.store.notifications.store.mongo.NotificationsMongo;
import org.finos.legend.depot.store.notifications.store.mongo.NotificationsStoreImpl;
import org.finos.legend.depot.store.resources.notifications.NotificationsManagerResource;
import org.finos.legend.depot.core.services.api.metrics.PrometheusMetricsHandler;
import org.finos.legend.depot.core.services.api.metrics.configuration.PrometheusConfiguration;

import javax.inject.Named;
import java.util.Arrays;

import static org.finos.legend.depot.store.notifications.services.NotificationsQueueManager.NOTIFICATIONS_COUNTER;
import static org.finos.legend.depot.store.notifications.services.NotificationsQueueManager.NOTIFICATIONS_COUNTER_HELP;
import static org.finos.legend.depot.store.notifications.services.NotificationsQueueManager.NOTIFICATION_COMPLETE;
import static org.finos.legend.depot.store.notifications.services.NotificationsQueueManager.NOTIFICATION_COMPLETE_HELP;
import static org.finos.legend.depot.store.notifications.services.NotificationsQueueManager.QUEUE_WAITING;
import static org.finos.legend.depot.store.notifications.services.NotificationsQueueManager.QUEUE_WAITING_HELP;

public class NotificationsModule extends PrivateModule
{

    @Override
    protected void configure()
    {

        bind(NotificationsManager.class).to(NotificationsQueueManager.class);
        bind(Notifications.class).to(NotificationsMongo.class);
        bind(NotificationsStore.class).to(NotificationsStoreImpl.class);
        bind(NotificationsManagerResource.class);

        expose(Notifications.class);
        expose(NotificationsManagerResource.class);
        expose(NotificationsManager.class);
    }


    @Provides
    @Named("notifications-metrics")
    @Singleton
    boolean registerMetrics(PrometheusConfiguration configuration)
    {
        if (configuration.isEnabled())
        {
            PrometheusMetricsHandler metricsHandler = configuration.getMetricsHandler();
            metricsHandler.registerCounter(NOTIFICATIONS_COUNTER, NOTIFICATIONS_COUNTER_HELP);
            metricsHandler.registerGauge(QUEUE_WAITING, QUEUE_WAITING_HELP);
            metricsHandler.registerHistogram(NOTIFICATION_COMPLETE, NOTIFICATION_COMPLETE_HELP, Arrays.asList("eventPriority"));
        }
        return true;
    }

    @Singleton
    @Provides
    @Named("register-indexes")
    public boolean registerIndexes(MongoAdminStore adminStore)
    {
        adminStore.registerIndexes(NotificationsMongo.COLLECTION,NotificationsMongo.buildIndexes());
        return true;
    }
}
