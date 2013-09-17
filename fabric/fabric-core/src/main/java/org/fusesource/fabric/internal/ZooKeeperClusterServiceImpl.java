/**
 * Copyright (C) FuseSource, Inc.
 * http://fusesource.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.fusesource.fabric.internal;

import static org.fusesource.fabric.utils.Ports.mapPortToRange;
import static org.fusesource.fabric.zookeeper.utils.ZooKeeperUtils.copy;
import static org.fusesource.fabric.zookeeper.utils.ZooKeeperUtils.exists;
import static org.fusesource.fabric.zookeeper.utils.ZooKeeperUtils.getStringData;
import static org.fusesource.fabric.zookeeper.utils.ZooKeeperUtils.getSubstitutedData;
import static org.fusesource.fabric.zookeeper.utils.ZooKeeperUtils.getSubstitutedPath;
import static org.fusesource.fabric.zookeeper.utils.ZooKeeperUtils.setData;

import java.io.File;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.api.ACLProvider;
import org.apache.curator.retry.RetryOneTime;
import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Deactivate;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.Service;
import org.apache.zookeeper.KeeperException;
import org.fusesource.fabric.api.Container;
import org.fusesource.fabric.api.CreateEnsembleOptions;
import org.fusesource.fabric.api.DataStore;
import org.fusesource.fabric.api.DataStoreRegistrationHandler;
import org.fusesource.fabric.api.DataStoreTemplate;
import org.fusesource.fabric.api.FabricException;
import org.fusesource.fabric.api.FabricService;
import org.fusesource.fabric.api.ZooKeeperClusterBootstrap;
import org.fusesource.fabric.api.ZooKeeperClusterService;
import org.fusesource.fabric.service.support.AbstractComponent;
import org.fusesource.fabric.service.support.ValidatingReference;
import org.fusesource.fabric.utils.Ports;
import org.fusesource.fabric.utils.SystemProperties;
import org.fusesource.fabric.zookeeper.ZkPath;
import org.osgi.service.cm.Configuration;
import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.component.ComponentContext;

@Component(name = "org.fusesource.fabric.zookeeper.cluster.service", description = "Fabric ZooKeeper Cluster Service")
@Service(ZooKeeperClusterService.class)
public class ZooKeeperClusterServiceImpl extends AbstractComponent implements ZooKeeperClusterService {

    @Reference(referenceInterface = ConfigurationAdmin.class)
    private final ValidatingReference<ConfigurationAdmin> configAdmin = new ValidatingReference<ConfigurationAdmin>();
    @Reference(referenceInterface = CuratorFramework.class)
    private final ValidatingReference<CuratorFramework> curator = new ValidatingReference<CuratorFramework>();
    @Reference(referenceInterface = ACLProvider.class)
    private final ValidatingReference<ACLProvider> aclProvider = new ValidatingReference<ACLProvider>();
    @Reference(referenceInterface = FabricService.class)
	private FabricService fabricService;
    @Reference(referenceInterface = DataStore.class)
    private DataStore dataStore;
    @Reference(referenceInterface = DataStoreRegistrationHandler.class)
    private final ValidatingReference<DataStoreRegistrationHandler> registrationHandler = new ValidatingReference<DataStoreRegistrationHandler>();
    @Reference(referenceInterface = ZooKeeperClusterBootstrap.class)
    private final ValidatingReference<ZooKeeperClusterBootstrap> bootstrap = new ValidatingReference<ZooKeeperClusterBootstrap>();

    @Activate
    synchronized void activate(ComponentContext context) {
        activateComponent(context);
    }

    @Deactivate
    synchronized void deactivate() {
        deactivateComponent();
    }

    void bindConfigAdmin(ConfigurationAdmin service) {
        this.configAdmin.set(service);
    }

    void unbindConfigAdmin(ConfigurationAdmin service) {
        this.configAdmin.set(null);
    }

    void bindFabricService(FabricService fabricService) {
		this.fabricService = fabricService;
	}

    void bindCurator(CuratorFramework curator) {
        this.curator.set(curator);
    }

    void unbindCurator(CuratorFramework curator) {
        this.curator.set(null);
    }

    void bindDataStore(DataStore dataStore) {
        this.dataStore = dataStore;
    }

    private DataStore getDataStore() {
        return dataStore;
    }

    public void clean() {
       bootstrap.get().clean();
    }

    private static void delete(File dir) {
        if (dir.isDirectory()) {
            for (File child : dir.listFiles()) {
                delete(child);
            }
        }
        if (dir.exists()) {
            dir.delete();
        }
    }

	public List<String> getEnsembleContainers() {
		try {
			Configuration[] configs = configAdmin.get().listConfigurations("(service.pid=org.fusesource.fabric.zookeeper)");
			if (configs == null || configs.length == 0) {
				return Collections.emptyList();
			}
			List<String> list = new ArrayList<String>();
			if (exists(curator.get(), ZkPath.CONFIG_ENSEMBLES.getPath()) != null) {
				String clusterId = getStringData(curator.get(), ZkPath.CONFIG_ENSEMBLES.getPath());
				String containers = getStringData(curator.get(), ZkPath.CONFIG_ENSEMBLE.getPath(clusterId));
				Collections.addAll(list, containers.split(","));
			}
			return list;
		} catch (Exception e) {
			throw new FabricException("Unable to load zookeeper quorum containers", e);
		}
	}

    public String getZooKeeperUrl() {
        return fabricService.getZookeeperUrl();
    }

    public void createCluster(List<String> containers) {
        createCluster(containers, CreateEnsembleOptions.builder().fromSystemProperties().build());
    }

    public void createCluster(final List<String> containers, CreateEnsembleOptions options) {
        final List<String> oldContainers = getEnsembleContainers();
        try {
            if (containers == null || containers.size() == 2) {
                throw new IllegalArgumentException("One or at least 3 containers must be used to create a zookeeper ensemble");
            }
            Configuration config = configAdmin.get().getConfiguration("org.fusesource.fabric.zookeeper", null);
            String zooKeeperUrl = config != null && config.getProperties() != null ? (String) config.getProperties().get("zookeeper.url") : null;
            if (zooKeeperUrl == null) {
                if (containers.size() != 1 || !containers.get(0).equals(System.getProperty(SystemProperties.KARAF_NAME))) {
                    throw new FabricException("The first zookeeper cluster must be configured on this container only.");
                }
                bootstrap.get().create(options);
                return;
            }

            Container[] allContainers = fabricService.getContainers();
            for (Container container : allContainers) {
                if (!container.isAliveAndOK()) {
                    throw new FabricException("Can not modify the zookeeper ensemble if all containers are not running");
                }
            }

            String version = getDataStore().getDefaultVersion();

            for (String container : containers) {
                Container c = fabricService.getContainer(container);
                if (exists(curator.get(), ZkPath.CONTAINER_ALIVE.getPath(container)) == null) {
                    throw new FabricException("The container " + container + " is not alive");
                }
                String containerVersion = getStringData(curator.get(), ZkPath.CONFIG_CONTAINER.getPath(container));
                if (!version.equals(containerVersion)) {
                    throw new FabricException("The container " + container + " is not using the default-version:" + version);
                }
            }

			// Find used zookeeper ports
			Map<String, List<Integer>> usedPorts = new HashMap<String, List<Integer>>();
			final String oldClusterId = getStringData(curator.get(), ZkPath.CONFIG_ENSEMBLES.getPath());
			if (oldClusterId != null) {
                String profile = "fabric-ensemble-" + oldClusterId;
                String pid = "org.fusesource.fabric.zookeeper.server-" + oldClusterId;

                Map<String, String> p = getDataStore().getConfiguration(version, profile, pid);

                if (p == null) {
                    throw new FabricException("Failed to find old cluster configuration for ID " + oldClusterId);
                }

				for (Object n : p.keySet()) {
					String node = (String) n;
					if (node.startsWith("server.")) {
                        String data = getSubstitutedData(curator.get(), dataStore.getConfigurations(version, "fabric-ensemble-" + oldClusterId).get("org.fusesource.fabric.zookeeper.server-" + oldClusterId).get(node));
						addUsedPorts(usedPorts, data);
					}
				}

                Map<String, String> zkConfig = getDataStore().getConfiguration(version, "default", "org.fusesource.fabric.zookeeper");
                if (zkConfig == null) {
                    throw new FabricException("Failed to find old zookeeper configuration in default profile");
                }
				String datas = getSubstitutedData(curator.get(), zkConfig.get("zookeeper.url"));
				for (String data : datas.split(",")) {
					addUsedPorts(usedPorts, data);
				}
			}

			String newClusterId;
			if (oldClusterId == null) {
				newClusterId = "0000";
			} else {
				newClusterId = new DecimalFormat("0000").format(Integer.parseInt(oldClusterId) + 1);
			}

            // create new ensemble
            String ensembleProfile = getDataStore().getProfile(version, "fabric-ensemble-" + newClusterId, true);
            getDataStore().setProfileAttribute(version, ensembleProfile, "abstract", "true");
            getDataStore().setProfileAttribute(version, ensembleProfile, "hidden", "true");

            Properties ensembleProperties = new Properties();
            ensembleProperties.put("tickTime", "2000");
            ensembleProperties.put("initLimit", "10");
            ensembleProperties.put("syncLimit", "5");
            ensembleProperties.put("dataDir", "data/zookeeper/" + newClusterId);

            int index = 1;
			String connectionUrl = "";
			String realConnectionUrl = "";
			String containerList = "";
			for (String container : containers) {
				String ip = getSubstitutedPath(curator.get(), ZkPath.CONTAINER_IP.getPath(container));

				String minimumPort = String.valueOf(Ports.MIN_PORT_NUMBER);
				String maximumPort = String.valueOf(Ports.MAX_PORT_NUMBER);
                String bindAddress = "0.0.0.0";

				if (exists(curator.get(), ZkPath.CONTAINER_PORT_MIN.getPath(container)) != null) {
					minimumPort = getSubstitutedPath(curator.get(), ZkPath.CONTAINER_PORT_MIN.getPath(container));
				}

				if (exists(curator.get(), ZkPath.CONTAINER_PORT_MAX.getPath(container)) != null) {
					maximumPort = getSubstitutedPath(curator.get(), ZkPath.CONTAINER_PORT_MAX.getPath(container));
				}

                if (exists(curator.get(), ZkPath.CONTAINER_BINDADDRESS.getPath(container)) != null) {
                    bindAddress= getSubstitutedPath(curator.get(), ZkPath.CONTAINER_BINDADDRESS.getPath(container));
                }

                String ensembleMemberConfigName = "org.fusesource.fabric.zookeeper.server-" + newClusterId + ".properties";
                Properties ensembleMemberProperties = new Properties();

                // configure this server in the ensemble
                String ensembleMemberProfile = getDataStore().getProfile(version, "fabric-ensemble-" + newClusterId + "-" + Integer.toString(index), true);
                getDataStore().setProfileAttribute(version, ensembleMemberProfile, "hidden", "true");
                getDataStore().setProfileAttribute(version, ensembleMemberProfile, "parents", ensembleProfile);

                String port1 = Integer.toString(findPort(usedPorts, ip, mapPortToRange(Ports.DEFAULT_ZOOKEEPER_SERVER_PORT, minimumPort, maximumPort)));
                if (containers.size() > 1) {
                    String port2 = Integer.toString(findPort(usedPorts, ip, mapPortToRange(Ports.DEFAULT_ZOOKEEPER_PEER_PORT, minimumPort, maximumPort)));
                    String port3 = Integer.toString(findPort(usedPorts, ip, mapPortToRange(Ports.DEFAULT_ZOOKEEPER_ELECTION_PORT, minimumPort, maximumPort)));
                    ensembleProperties.put("server." + Integer.toString(index), "${zk:" + container + "/ip}:" + port2 + ":" + port3);
                    ensembleMemberProperties.put("server.id", Integer.toString(index));
                }
                ensembleMemberProperties.put("clientPort", port1);
                ensembleMemberProperties.put("clientPortAddress", bindAddress);

                getDataStore().setFileConfiguration(version, ensembleMemberProfile, ensembleMemberConfigName, DataStoreHelpers.toBytes(ensembleMemberProperties));

				if (connectionUrl.length() > 0) {
					connectionUrl += ",";
					realConnectionUrl += ",";
				}
				connectionUrl += "${zk:" + container + "/ip}:" + port1;
				realConnectionUrl += ip + ":" + port1;
				if (containerList.length() > 0) {
					containerList += ",";
				}
				containerList += container;
				index++;
			}

            String ensembleConfigName = "org.fusesource.fabric.zookeeper.server-" + newClusterId + ".properties";
            getDataStore().setFileConfiguration(version, ensembleProfile, ensembleConfigName, DataStoreHelpers.toBytes(ensembleProperties) );

            index = 1;
            for (String container : containers) {
                // add this container to the ensemble
                List<String> profiles = new LinkedList<String>(dataStore.getContainerProfiles(container));
                profiles.add("fabric-ensemble-" + newClusterId + "-" + Integer.toString(index));
                dataStore.setContainerProfiles(container, profiles);
                index++;
            }

            if (oldClusterId != null) {
                Properties properties = DataStoreHelpers.toProperties(getDataStore().getConfiguration(version, "default", "org.fusesource.fabric.zookeeper"));
				properties.put("zookeeper.url", getSubstitutedData(curator.get(), realConnectionUrl));
				properties.put("zookeeper.password", options.getZookeeperPassword());
				CuratorFramework dst = CuratorFrameworkFactory.builder().connectString(realConnectionUrl)
                                                              .retryPolicy(new RetryOneTime(500))
                                                              .aclProvider(aclProvider.get())
                                                              .authorization("digest", ("fabric:" + options.getZookeeperPassword()).getBytes())
                                                              .sessionTimeoutMs(30000)
                                                              .connectionTimeoutMs(30000).build();
                dst.start();
				try {
					dst.getZookeeperClient().blockUntilConnectedOrTimedOut();

					copy(curator.get(), dst, "/fabric");
					setData(dst, ZkPath.CONFIG_ENSEMBLES.getPath(), newClusterId);
                    setData(dst, ZkPath.CONFIG_ENSEMBLE.getPath(newClusterId), containerList);

                    // Perform cleanup when the new datastore has been registered.
                    final AtomicReference<DataStore> result = new AtomicReference<DataStore>();
                    registrationHandler.get().addRegistrationCallback(new DataStoreTemplate() {
                        @Override
                        public void doWith(DataStore dataStore) throws Exception {
                            synchronized (result) {
                                result.set(dataStore);
                                result.notifyAll();
                            }
                        }
                    });

                    setData(dst, ZkPath.CONFIG_ENSEMBLE_PASSWORD.getPath(), options.getZookeeperPassword());
                    setData(dst, ZkPath.CONFIG_ENSEMBLE_URL.getPath(), connectionUrl);
                    setData(curator.get(), ZkPath.CONFIG_ENSEMBLE_PASSWORD.getPath(), options.getZookeeperPassword());
                    setData(curator.get(), ZkPath.CONFIG_ENSEMBLE_URL.getPath(), connectionUrl);

                    // Wait until all containers switched
                    long t0 = System.currentTimeMillis();
                    boolean allStarted = false;
                    while (!allStarted && System.currentTimeMillis() - t0 < 60 * 1000) {
                        allStarted = true;
                        for (Container container : allContainers) {
                            allStarted &= exists(dst, ZkPath.CONTAINER_ALIVE.getPath(container.getId())) != null;
                        }
                        if (!allStarted) {
                            Thread.sleep(1000);
                        }
                    }
                    if (!allStarted) {
                        throw new FabricException("Timeout waiting for containers to join the new ensemble");
                    }

                    // Wait until the new datastore has been registered
                    synchronized (result) {
                        if (result.get() == null) {
                            result.wait();
                        }
                    }
                    // Remove old profiles
                    for (String container : oldContainers) {
                        cleanUpEnsembleProfiles(result.get(), container, oldClusterId);
                    }

                } finally {
					dst.close();
				}
			} else {
                Map<String, String> zkConfig = dataStore.getConfiguration(version, "default", "org.fusesource.fabric.zookeeper");
                zkConfig.put("zookeeper.password", "${zk:" + ZkPath.CONFIG_ENSEMBLE_PASSWORD.getPath() + "}");
                zkConfig.put("zookeeper.url", "${zk:" + ZkPath.CONFIG_ENSEMBLE_URL.getPath() + "}");
                dataStore.setConfiguration(version, "default", "org.fusesource.fabric.zookeeper", zkConfig);
			}
		} catch (Exception e) {
			throw new FabricException("Unable to create zookeeper quorum: " + e.getMessage(), e);
		}
	}

    /**
     * Removes all ensemble profiles matching the clusterId from the container.
     * @param dataStore The dataStore to use.
     * @param container The target container.
     * @param clusterId The clusterId to be removed.
     */
    private void cleanUpEnsembleProfiles(DataStore dataStore, String container, String clusterId) {
        List<String> profiles = new LinkedList<String>(dataStore.getContainerProfiles(container));
        List<String> toRemove = new LinkedList<String>();
        for (String p : profiles) {
            if (p.startsWith("fabric-ensemble-" + clusterId)) {
                toRemove.add(p);
            }
        }
        profiles.removeAll(toRemove);
        dataStore.setContainerProfiles(container, profiles);
    }

    public static Properties getProperties(CuratorFramework client, String file, Properties defaultValue) throws Exception {
        try {
            String v = getStringData(client, file);
            if (v != null) {
                return DataStoreHelpers.toProperties(v);
            } else {
                return defaultValue;
            }
        } catch (KeeperException.NoNodeException e) {
            return defaultValue;
        }
    }

    private int findPort(Map<String, List<Integer>> usedPorts, String ip, int port) {
        List<Integer> ports = usedPorts.get(ip);
        if (ports == null) {
            ports = new ArrayList<Integer>();
            usedPorts.put(ip, ports);
        }
        for (; ; ) {
            if (!ports.contains(port)) {
                ports.add(port);
                return port;
            }
            port++;
        }
    }

    private void addUsedPorts(Map<String, List<Integer>> usedPorts, String data) {
        String[] parts = data.split(":");
        List<Integer> ports = usedPorts.get(parts[0]);
        if (ports == null) {
            ports = new ArrayList<Integer>();
            usedPorts.put(parts[0], ports);
        }
        for (int i = 1; i < parts.length; i++) {
            ports.add(Integer.parseInt(parts[i]));
        }
    }

    public void addToCluster(List<String> containers) {

        CreateEnsembleOptions options = CreateEnsembleOptions.builder()
                .zookeeperPassword(fabricService.getZookeeperPassword())
                .build();
		addToCluster(containers, options);
	}

	/**
	 * Adds the containers to the cluster.
	 *
	 * @param containers
	 */
	@Override
	public void addToCluster(List<String> containers, CreateEnsembleOptions options) {
		try {
			List<String> current = getEnsembleContainers();
			current.addAll(containers);
			createCluster(current, options);
		} catch (Exception e) {
			throw new FabricException("Unable to add containers to fabric ensemble: " + e.getMessage(), e);
		}
	}

	public void removeFromCluster(List<String> containers) {
		CreateEnsembleOptions options = CreateEnsembleOptions.builder()
                                                             .zookeeperPassword(fabricService.getZookeeperPassword())
                                                             .build();
		removeFromCluster(containers, options);
	}

	/**
	 * Removes the containers from the cluster.
	 *
	 * @param containers
	 */
	@Override
	public void removeFromCluster(List<String> containers, CreateEnsembleOptions options) {
		try {
			List<String> current = getEnsembleContainers();
			current.removeAll(containers);
			createCluster(current, options);
		} catch (Exception e) {
			throw new FabricException("Unable to remove containers to fabric ensemble: " + e.getMessage(), e);
		}
	}

    void bindBootstrap(ZooKeeperClusterBootstrap bootstrap) {
        this.bootstrap.set(bootstrap);
    }

    void unbindBootstrap(ZooKeeperClusterBootstrap bootstrap) {
        this.bootstrap.set(null);
    }

    void bindAclProvider(ACLProvider aclProvider) {
        this.aclProvider.set(aclProvider);
    }

    void unbindAclProvider(ACLProvider aclProvider) {
        this.aclProvider.set(null);
    }

    void bindRegistrationHandler(DataStoreRegistrationHandler service) {
        this.registrationHandler.set(service);
    }

    void unbindRegistrationHandler(DataStoreRegistrationHandler service) {
        this.registrationHandler.set(null);
    }
}
