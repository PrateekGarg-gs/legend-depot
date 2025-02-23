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

package org.finos.legend.depot.services.artifacts.refresh;

import org.finos.legend.depot.domain.project.ProjectVersion;
import org.finos.legend.depot.services.api.artifacts.refresh.RefreshDependenciesService;
import org.finos.legend.depot.services.api.projects.ManageProjectsService;
import org.finos.legend.depot.services.dependencies.DependencyUtil;
import org.finos.legend.depot.services.projects.ManageProjectsServiceImpl;
import org.finos.legend.depot.services.api.projects.configuration.ProjectsConfiguration;
import org.finos.legend.depot.store.api.projects.UpdateProjects;
import org.finos.legend.depot.store.api.projects.UpdateProjectsVersions;
import org.finos.legend.depot.services.api.artifacts.repository.ArtifactRepository;
import org.finos.legend.depot.services.api.metrics.query.QueryMetricsRegistry;
import org.finos.legend.depot.store.model.projects.StoreProjectData;
import org.finos.legend.depot.store.model.projects.StoreProjectVersionData;
import org.finos.legend.depot.store.mongo.CoreDataMongoStoreTests;
import org.finos.legend.depot.store.mongo.projects.ProjectsMongo;
import org.finos.legend.depot.store.mongo.projects.ProjectsVersionsMongo;
import org.finos.legend.depot.store.notifications.queue.api.Queue;
import org.finos.legend.depot.store.notifications.queue.store.mongo.NotificationsQueueMongo;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.mockito.Mockito.mock;

public class TestRefreshDependenciesService extends CoreDataMongoStoreTests
{
    protected UpdateProjects projectsStore = new ProjectsMongo(mongoProvider);
    protected UpdateProjectsVersions projectsVersionsStore = new ProjectsVersionsMongo(mongoProvider);
    private final QueryMetricsRegistry metrics = mock(QueryMetricsRegistry.class);
    protected Queue queue = new NotificationsQueueMongo(mongoProvider);
    protected ManageProjectsService projectsService = new ManageProjectsServiceImpl(projectsVersionsStore,projectsStore,metrics,queue,new ProjectsConfiguration("master"));
    protected ArtifactRepository repository = mock(ArtifactRepository.class);
    protected RefreshDependenciesService refreshDependenciesService = new RefreshDependenciesServiceImpl(projectsService, repository,new DependencyUtil());

    private static final String GROUPID = "examples.metadata";

    @Before
    public void setUpData()
    {
        List<StoreProjectVersionData> projectVersionData = readProjectVersionsConfigsFile(this.getClass().getClassLoader().getResource("data/testProjectsVersions.json"));
        projectVersionData.forEach(pv -> this.projectsVersionsStore.createOrUpdate(pv));
        List<StoreProjectData> projectData = readProjectConfigsFile(this.getClass().getClassLoader().getResource("data/testProjects.json"));
        projectData.forEach(p -> this.projectsStore.createOrUpdate(p));
        Assert.assertEquals(5, projectsVersionsStore.getAll().size());
    }

    @Test
    public void canUpdateTransitiveDependencies()
    {
        //Adding a new project
        StoreProjectVersionData project1 = new StoreProjectVersionData(GROUPID, "test-master", "3.0.0");
        ProjectVersion dependency = new ProjectVersion(GROUPID, "test", "3.0.0");
        project1.getVersionData().setDependencies(Collections.singletonList(dependency));
        projectsVersionsStore.createOrUpdate(project1);

        StoreProjectVersionData versionData = refreshDependenciesService.updateTransitiveDependencies(GROUPID, "test-master", "3.0.0");
        Assert.assertTrue(versionData.getTransitiveDependenciesReport().isValid());
        List<ProjectVersion> transitiveDependencies = versionData.getTransitiveDependenciesReport().getTransitiveDependencies();
        Assert.assertEquals(5, transitiveDependencies.size());
        List<ProjectVersion> dependencies = Arrays.asList(dependency, new ProjectVersion(GROUPID, "test-dependencies", "2.0.0"), new ProjectVersion(GROUPID, "art101", "1.0.0"),new ProjectVersion(GROUPID, "art102", "1.0.0"), new ProjectVersion(GROUPID, "art103", "1.0.0"));
        Assert.assertEquals(transitiveDependencies, dependencies);
    }

    @Test
    public void canUpdateTransitiveDependenciesForRevision()
    {
        projectsVersionsStore.createOrUpdate(new StoreProjectVersionData(GROUPID, "art104", "1.0.0"));
        //Adding a new project
        StoreProjectVersionData project1 = new StoreProjectVersionData(GROUPID, "test-master", "branch1-SNAPSHOT");
        ProjectVersion dependency1 = new ProjectVersion(GROUPID, "art104", "1.0.0");
        project1.getVersionData().setDependencies(Collections.singletonList(dependency1));
        projectsVersionsStore.createOrUpdate(project1);

        //this depends on master snapshot
        StoreProjectVersionData project2 = new StoreProjectVersionData(GROUPID, "art105", "branch1-SNAPSHOT");
        ProjectVersion dependency2 = new ProjectVersion(GROUPID, "test-master", "branch1-SNAPSHOT");
        project2.getVersionData().setDependencies(Collections.singletonList(dependency2));
        projectsVersionsStore.createOrUpdate(project2);

        //this depends on master snapshot
        StoreProjectVersionData project3 = new StoreProjectVersionData(GROUPID, "art106", "branch1-SNAPSHOT");
        ProjectVersion dependency3 = new ProjectVersion(GROUPID, "art105", "branch1-SNAPSHOT");
        project3.getVersionData().setDependencies(Collections.singletonList(dependency3));
        projectsVersionsStore.createOrUpdate(project3);

        project1 = refreshDependenciesService.updateTransitiveDependencies(GROUPID, "test-master", "branch1-SNAPSHOT");
        Assert.assertTrue(project1.getTransitiveDependenciesReport().isValid());
        Assert.assertEquals(Collections.singletonList(dependency1), project1.getTransitiveDependenciesReport().getTransitiveDependencies());
        //chain update of snapshot dependants
        project2 = projectsService.find(GROUPID, "art105", "branch1-SNAPSHOT").get();
        Assert.assertTrue(project2.getTransitiveDependenciesReport().isValid());
        Assert.assertEquals(Arrays.asList(dependency1, dependency2), project2.getTransitiveDependenciesReport().getTransitiveDependencies());

        project3 = projectsService.find(GROUPID, "art106", "branch1-SNAPSHOT").get();
        Assert.assertTrue(project3.getTransitiveDependenciesReport().isValid());
        Assert.assertEquals(Arrays.asList(dependency1, dependency3, dependency2), project3.getTransitiveDependenciesReport().getTransitiveDependencies());
    }

    @Test
    public void canOverrideDependencies1()
    {
        StoreProjectVersionData project1 = new StoreProjectVersionData(GROUPID, "art102", "2.0.0");
        ProjectVersion dependency = new ProjectVersion(GROUPID, "test", "1.0.0");
        project1.getVersionData().setDependencies(Collections.singletonList(dependency));
        projectsService.createOrUpdate(project1);
        project1 = refreshDependenciesService.updateTransitiveDependencies(GROUPID, "art102", "2.0.0");
        Assert.assertTrue(project1.getTransitiveDependenciesReport().isValid());
        Assert.assertEquals(Collections.singletonList(dependency), project1.getTransitiveDependenciesReport().getTransitiveDependencies());

        //overriding test:1.0.0 dependency with test:3.0.0
        StoreProjectVersionData project2 = new StoreProjectVersionData(GROUPID, "test-master", "3.0.0");
        ProjectVersion dependency1 = new ProjectVersion(GROUPID, "test", "3.0.0");
        ProjectVersion dependency2 = new ProjectVersion(GROUPID, "art102", "2.0.0");
        project2.getVersionData().setDependencies(Arrays.asList(dependency1, dependency2));
        projectsService.createOrUpdate(project2);

        project2 = refreshDependenciesService.updateTransitiveDependencies(GROUPID, "test-master", "3.0.0");
        Assert.assertTrue(project2.getTransitiveDependenciesReport().isValid());
        Assert.assertEquals(Arrays.asList(dependency1, dependency2, new ProjectVersion(GROUPID, "test-dependencies", "2.0.0"), new ProjectVersion(GROUPID, "art101", "1.0.0")), project2.getTransitiveDependenciesReport().getTransitiveDependencies());
    }

    @Test
    public void canOverrideDependencies2()
    {
        projectsService.createOrUpdate(new StoreProjectVersionData(GROUPID, "art104", "1.0.0"));

        StoreProjectVersionData project1 = new StoreProjectVersionData(GROUPID, "art102", "2.0.0");
        ProjectVersion dependency = new ProjectVersion(GROUPID, "art104", "1.0.0");
        project1.getVersionData().setDependencies(Collections.singletonList(dependency));
        projectsService.createOrUpdate(project1);
        project1 = refreshDependenciesService.updateTransitiveDependencies(GROUPID, "art102", "2.0.0");
        Assert.assertTrue(project1.getTransitiveDependenciesReport().isValid());
        Assert.assertEquals(Collections.singletonList(dependency), project1.getTransitiveDependenciesReport().getTransitiveDependencies());

        //overriding art102:1.0.0 dependency with art102:2.0.0 with a different underlying dependency
        StoreProjectVersionData project2 = new StoreProjectVersionData(GROUPID, "test-master", "3.0.0");
        ProjectVersion dependency1 = new ProjectVersion(GROUPID, "test", "3.0.0");
        ProjectVersion dependency2 = new ProjectVersion(GROUPID, "art102", "2.0.0");
        project2.getVersionData().setDependencies(Arrays.asList(dependency1, dependency2));
        projectsService.createOrUpdate(project2);

        project2 = refreshDependenciesService.updateTransitiveDependencies(GROUPID, "test-master", "3.0.0");
        Assert.assertTrue(project2.getTransitiveDependenciesReport().isValid());
        Assert.assertEquals(Arrays.asList(dependency1, dependency, dependency2, new ProjectVersion(GROUPID, "test-dependencies", "2.0.0"), new ProjectVersion(GROUPID, "art101", "1.0.0")), project2.getTransitiveDependenciesReport().getTransitiveDependencies());
    }

//    Example : A -> B_V1 -> C_V1
//    B_V2->C_V2
//    C depends on A and B_V2
//    Result: C depends on A and B_V2 and C_V2
    @Test
    public void canOverrideDependencies3()
    {
        projectsService.createOrUpdate(new StoreProjectVersionData(GROUPID, "art104", "1.0.0"));

        StoreProjectVersionData project1 = new StoreProjectVersionData(GROUPID, "art102", "1.0.0");
        ProjectVersion dependency = new ProjectVersion(GROUPID, "art104", "1.0.0");
        project1.getVersionData().setDependencies(Collections.singletonList(dependency));
        projectsService.createOrUpdate(project1);
        project1 = refreshDependenciesService.updateTransitiveDependencies(GROUPID, "art102", "1.0.0");
        Assert.assertTrue(project1.getTransitiveDependenciesReport().isValid());
        Assert.assertEquals(Collections.singletonList(dependency), project1.getTransitiveDependenciesReport().getTransitiveDependencies());

        projectsService.createOrUpdate(new StoreProjectVersionData(GROUPID, "art104", "2.0.0"));

        StoreProjectVersionData project2 = new StoreProjectVersionData(GROUPID, "art102", "2.0.0");
        ProjectVersion dependency1 = new ProjectVersion(GROUPID, "art104", "2.0.0");
        project2.getVersionData().setDependencies(Collections.singletonList(dependency1));
        projectsService.createOrUpdate(project2);
        project2 = refreshDependenciesService.updateTransitiveDependencies(GROUPID, "art102", "2.0.0");
        Assert.assertTrue(project2.getTransitiveDependenciesReport().isValid());
        Assert.assertEquals(Collections.singletonList(dependency1), project2.getTransitiveDependenciesReport().getTransitiveDependencies());

        StoreProjectVersionData project3 = new StoreProjectVersionData(GROUPID, "test", "5.0.0");
        ProjectVersion dependency2 = new ProjectVersion(GROUPID, "art102", "1.0.0");
        project3.getVersionData().setDependencies(Collections.singletonList(dependency2));
        projectsService.createOrUpdate(project3);
        project3 = refreshDependenciesService.updateTransitiveDependencies(GROUPID, "test", "5.0.0");
        Assert.assertTrue(project3.getTransitiveDependenciesReport().isValid());
        Assert.assertEquals(Arrays.asList(dependency, dependency2), project3.getTransitiveDependenciesReport().getTransitiveDependencies());

        //overriding art102:1.0.0 dependency with art102:2.0.0 with a different underlying dependency
        StoreProjectVersionData project4 = new StoreProjectVersionData(GROUPID, "test-master", "3.0.0");
        ProjectVersion dependency3 = new ProjectVersion(GROUPID, "test", "5.0.0");
        ProjectVersion dependency4 = new ProjectVersion(GROUPID, "art102", "2.0.0");
        project4.getVersionData().setDependencies(Arrays.asList(dependency3, dependency4));
        projectsService.createOrUpdate(project4);

        project4 = refreshDependenciesService.updateTransitiveDependencies(GROUPID, "test-master", "3.0.0");
        Assert.assertTrue(project4.getTransitiveDependenciesReport().isValid());
        Assert.assertEquals(Arrays.asList(dependency3, dependency4, dependency1), project4.getTransitiveDependenciesReport().getTransitiveDependencies());
    }
}
