package com.syj.tcpentrypoint.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.syj.tcpentrypoint.config.ClientEndpointConfig;
import com.syj.tcpentrypoint.error.ClientEndpointClosedException;
import com.syj.tcpentrypoint.error.IllegalConfigureException;
import com.syj.tcpentrypoint.error.InitErrorException;
import com.syj.tcpentrypoint.error.NoAliveEndpointException;
import com.syj.tcpentrypoint.error.RpcException;
import com.syj.tcpentrypoint.msg.RequestMessage;
import com.syj.tcpentrypoint.msg.ResponseMessage;
import com.syj.tcpentrypoint.transport.ClientEndpointFactory;
import com.syj.tcpentrypoint.transport.ClientEndpointTransport;
import com.syj.tcpentrypoint.util.ExceptionUtils;
import com.syj.tcpentrypoint.util.NamedThreadFactory;
import com.syj.tcpentrypoint.util.NetUtils;
import com.syj.tcpentrypoint.util.StringUtils;



/**
 * 
 * @des :实现了客户端下长连接的维护，长连接选择,子类需要实现具体的规则
 * @author:shenyanjun1
 * @date :2018-12-17 10:03
 */
public abstract class ClientEndpoint {

	/**
	 * slf4j logger for this class
	 */
	private final static Logger LOGGER = LoggerFactory.getLogger(ClientEndpoint.class);

	/**
	 * 连接管理器
	 */
	protected ConnectionHolder connectionHolder;

	/**
	 * 当前服务集群对应的client config
	 */
	protected final ClientEndpointConfig clientConfig;

	/**
	 * 是否已启动(已建立连接)
	 */
	protected volatile boolean inited = false;

	/**
	 * 是否已经销毁（已经销毁不能再继续使用）
	 */
	protected volatile boolean destroyed = false;

	/**
	 * 当前Client正在发送的调用数量
	 */
	protected AtomicInteger countOfInvoke = new AtomicInteger(0);

	/**
	 * 负载均衡接口
	 */
	private volatile Loadbalance loadbalance;

	/**
	 * 构造方法
	 *
	 * @param consumerConfig 客户端配置
	 */
	public ClientEndpoint(ClientEndpointConfig clientConfig) {
		this.clientConfig = clientConfig;
		// 负载均衡策略 考虑是否可动态替换？
		String lb = clientConfig.getLoadbalance();
		loadbalance = Loadbalance.getInstance(lb);
		loadbalance.setConsumerConfig(clientConfig);
		// 连接管理器
		connectionHolder = new ConnectionHolder(clientConfig);
		initConnections();

	}

	/**
	 * 和服务端建立连接
	 */
	private void initConnections() {
		if (destroyed) { // 已销毁
			throw new RpcException("Client has been destroyed!");
		}
		if (inited) { // 已初始化
			return;
		}
		synchronized (this) {
			if (inited) {
				return;
			}
			try {
				// 得到服务端列表
				List<Endpoint> tmpProviderList = buildProviderList();
				connectToProviders(tmpProviderList); // 初始化服务端连接（建立长连接)
			} catch (InitErrorException e) {
				throw e;
			} catch (Exception e) {
				throw new InitErrorException("init provider's transport error!", e);
			}

			// 启动重连线程
			connectionHolder.startReconnectThread();
			// 启动成功
			inited = true;
		}
	}

	/**
	 * 
	 * @return server list
	 */
	protected List<Endpoint> buildProviderList() {
		List<Endpoint> tmpProviderList = new ArrayList<Endpoint>();
		String svrList = clientConfig.getServerList();
		if (StringUtils.isNotEmpty(svrList)) {
			String[] providerStrs = StringUtils.splitWithCommaOrSemicolon(svrList);
			for (int i = 0; i < providerStrs.length; i++) {
				String[] server = StringUtils.split(providerStrs[i],":");
				if (server.length != 2) {
					throw new IllegalConfigureException(10001, "server  ", providerStrs[i], " ip and port no match.");

				}
				String ip = server[0];
				String port = server[1];
				Endpoint provider = Endpoint.getProvider(ip, Integer.parseInt(port));
				tmpProviderList.add(provider);
			}
		}
		return tmpProviderList;
	}

	/**
	 * 增加Provider
	 *
	 * @param providers Provider列表
	 */
	public void addProvider(List<Endpoint> providers) {

	}

	/**
	 * 删除Provider
	 *
	 * @param providers Provider列表
	 */
	public void removeProvider(List<Endpoint> providers) {

	}

	/**
	 * 更新Provider
	 *
	 * @param newProviders Provider列表
	 */
	public void updateProvider(List<Endpoint> newProviders) {

	}

	/**
	 * 连接服务端，建立Connection
	 *
	 * @param providerList 服务端列表
	 */
	protected void connectToProviders(List<Endpoint> endpointList) {
		final String appName = clientConfig.getAppName();
		int endPointSize = endpointList.size();
		LOGGER.info("Init provider of {},size is : {}", appName, endPointSize);
		if (endPointSize > 0) {
			// 多线程建立连接
			int threads = Math.min(10, endPointSize); // 最大10个
			final CountDownLatch latch = new CountDownLatch(endPointSize);
			ThreadPoolExecutor initPool = new ThreadPoolExecutor(threads, threads, 0L, TimeUnit.MILLISECONDS,
					new LinkedBlockingQueue<Runnable>(endpointList.size()),
					new NamedThreadFactory("RULESENGINE-CLI-CONN-" + appName, true));
			for (final Endpoint endpoint : endpointList) {
				final ClientEndpointConfig config = endpointToClientConfig(endpoint,clientConfig);
				initPool.execute(new Runnable() {
					@Override
					public void run() {
						try {
							ClientEndpointTransport transport = ClientEndpointFactory.getClientTransport(config);
							if (connectionHolder.doubleCheck(endpoint, transport)) {
								printSuccess(endpoint, transport);
								connectionHolder.addAlive(endpoint, transport);
							} else {
								printFailure(endpoint, transport);
								connectionHolder.addRetry(endpoint, transport);
							}
						} catch (Exception e) {
							printDead(endpoint, e);
							connectionHolder.addDead(endpoint, clientConfig);
						} finally {
							latch.countDown(); // 连上或者抛异常
						}
					}
				});
			}

			try {
				int totalTimeout = ((endPointSize % threads == 0) ? (endPointSize / threads)
						: ((endPointSize / threads) + 1)) * clientConfig.getConnectTimeout() + 500;
				latch.await(totalTimeout, TimeUnit.MILLISECONDS); // 一直等到子线程都结束
			} catch (InterruptedException e) {
				LOGGER.error("Exception when init provider", e);
			} finally {
				initPool.shutdown(); // 关闭线程池
			}
		}
	}

	/**
	 * 打印连接成功日志
	 *
	 * @param provider  服务端
	 * @param transport 连接
	 */
	private void printSuccess(Endpoint provider, ClientEndpointTransport transport) {
		LOGGER.info(
				"Connect to  provider:{} success ! The connection is "
						+ NetUtils.connectToString(transport.getRemoteAddress(), transport.getLocalAddress()),
				provider);
	}

	/**
	 * 打印连接失败日志
	 *
	 * @param provider  服务端
	 * @param transport 连接
	 */
	private void printFailure(Endpoint provider, ClientEndpointTransport transport) {
		LOGGER.info("Connect to {} provider:{} failure !", provider);
	}

	/**
	 * 打印连不上日志
	 * 
	 * @param provider 服务端
	 */
	private void printDead(Endpoint provider, Exception e) {
		Throwable cause = e.getCause();
		LOGGER.warn("Connect to {} provider:{} failure !! The exception is " + ExceptionUtils.toShortString(e, 1)
				+ (cause != null ? ", cause by " + cause.getMessage() + "." : "."), provider);
	}

	/**
	 * 是否可用（即有可用的服务端）
	 *
	 * @return 是/否
	 */
	public boolean isAvailable() {
		if (destroyed || !inited)
			return false;
		if (connectionHolder.isAliveEmpty())
			return false;
		for (Map.Entry<Endpoint, ClientEndpointTransport> entry : connectionHolder.getAliveConnections().entrySet()) {
			Endpoint provider = entry.getKey();
			ClientEndpointTransport transport = entry.getValue();
			if (transport.isOpen()) {
				return true;
			} else {
				connectionHolder.aliveToRetryIfExist(provider, transport);
			}
		}
		return false;
	}

	/**
	 * 检查状态是否变化，变化则通知监听器
	 *
	 * @param originalState 原始状态
	 */
	public void checkStateChange(boolean originalState) {
		if (originalState) { // 原来可以
			if (!isAvailable()) { // 变不可以
				connectionHolder.notifyStateChangeToUnavailable();
			}
		} else { // 原来不可用
			if (isAvailable()) { // 变成可用
				connectionHolder.notifyStateChangeToAvailable();
			}
		}
	}

	/**
	 * 调用
	 *
	 * @param msg Request对象
	 * @return 调用结果
	 */
	public ResponseMessage sendMsg(RequestMessage msg) {
		// 做一些初始化检查，例如未连接可以连接
		try {
			countOfInvoke.incrementAndGet(); // 计数
			return doSendMsg(msg);
		} catch (Exception e) {
			throw e;
		}
		finally {
			countOfInvoke.decrementAndGet();
		}
	}

	/**
	 * 子类实现各自逻辑的调用，例如重试等
	 *
	 * @param msg Request对象
	 * @return 调用结果
	 */
	protected abstract ResponseMessage doSendMsg(RequestMessage msg);

	/**
	 * 调用客户端
	 *
	 * @param connection 客户端连接
	 * @param msg        Request对象
	 * @return 调用结果
	 */
	protected ResponseMessage sendMsg0(Connection connection, RequestMessage msg) {
		Endpoint provider = connection.getEndPoint();
		ClientEndpointTransport transport = connection.getTransport();
		try {
			int timeout = clientConfig.getInvokeTimeout();//3s
			ResponseMessage response = transport.send(msg, timeout);
			return response;
		} catch (ClientEndpointClosedException e) { // 连接断开异常
			connectionHolder.aliveToRetryIfExist(provider, transport);
			throw e;
		}
	}

	/**
	 * 上一次连接，目前是记录整个接口的，是否需要方法级的？？
	 */
	private volatile Endpoint lastProvider;

	/**
	 * 根据规则进行负载均衡
	 *
	 * @param message 调用对象
	 * @return 一个可用的provider
	 * @throws NoAliveEndpointException 没有可用的服务端
	 */
	protected Connection select(RequestMessage message) throws NoAliveEndpointException {
		return select(message, null);
	}

	/**
	 * 根据规则进行负载均衡
	 *
	 * @param message          调用对象
	 * @param invokedProviders 已调用列表
	 * @return 一个可用的provider
	 * @throws NoAliveEndpointException 没有可用的服务端
	 */
	protected Connection select(RequestMessage message, List<Endpoint> invokedProviders)
			throws NoAliveEndpointException {

		// 原始服务列表数据
		List<Endpoint> providers = connectionHolder.getAliveProviders();
		if (providers.size() == 0) {
			throw new NoAliveEndpointException(clientConfig.getAppName(), connectionHolder.currentProviderList());
		}
		do {
			// 再进行负载均衡筛选
			Endpoint provider = loadbalance.select(providers);
			ClientEndpointTransport transport = selectByProvider(message, provider);
			if (transport != null) {
				return new Connection(provider, transport);
			} else { // 这个provider表示有问题，可能已经不可用，本次轮询不再选到
				providers.remove(provider);
			}
		} while (providers.size() > 0); // 只判断筛选后的列表
		throw new NoAliveEndpointException(clientConfig.getAppName(), connectionHolder.currentProviderList());
	}

	/**
	 * 得到provider得到连接
	 *
	 * @param message  调用对象
	 * @param provider 指定Provider
	 * @return 一个可用的transport或者null
	 */
	protected ClientEndpointTransport selectByProvider(RequestMessage message, Endpoint provider) {
		ClientEndpointTransport transport = connectionHolder.getAliveClientTransport(provider);
		if (transport != null) {
			if (transport.isOpen()) {
				lastProvider = provider;
				return transport;
			} else {
				connectionHolder.aliveToRetryIfExist(provider, transport);
			}
		}
		return null;
	}

	/**
	 * 销毁方法
	 */
	public void destroy() {
		if (destroyed) {
			return;
		}
		// 销毁重连client线程
		connectionHolder.shutdownReconnectThread();
		destroyed = true;
		// 关闭已有连接
		closeTransports();
		inited = false;
	}

	/**
	 * 关闭连接<br/>
	 * 注意：关闭有风险，可能有正在调用的请求，建议判断下isAlivable()
	 */
	protected void closeTransports() {
		// 清空列表先
		HashMap<Endpoint, ClientEndpointTransport> all = connectionHolder.clear();

		// 准备关闭连接
		int count = countOfInvoke.get();
		final int timeout = clientConfig.getDisconnectTimeout(); // 等待结果超时时间
		if (count > 0) { // 有正在调用的请求
			long start = System.currentTimeMillis();
			LOGGER.warn("There are {} outstanding call in client, will close transports util return", count);
			while (countOfInvoke.get() > 0 && System.currentTimeMillis() - start < timeout) { // 等待返回结果
				try {
					Thread.sleep(10);
				} catch (InterruptedException e) {
				}
			}
		}
		// 多线程销毁已经建立的连接
		int providerSize = all.size();
		if (providerSize > 0) {
			int threads = Math.min(10, providerSize); // 最大10个
			final CountDownLatch latch = new CountDownLatch(providerSize);
			ThreadPoolExecutor closepool = new ThreadPoolExecutor(threads, threads, 0L, TimeUnit.MILLISECONDS,
					new LinkedBlockingQueue<Runnable>(providerSize),
					new NamedThreadFactory("RULESENGINE-CLI-DISCONN-" + clientConfig.getAppName(), true));
			for (Entry<Endpoint, ClientEndpointTransport> entry : all.entrySet()) {
				final Endpoint provider = entry.getKey();
				final ClientEndpointTransport transport = entry.getValue();
				closepool.execute(new Runnable() {
					@Override
					public void run() {
						try {
							ClientEndpointFactory.releaseTransport(transport, 0);
						} catch (Exception e) {
							LOGGER.warn("catch exception but ignore it when close alive client : {}", provider);
						} finally {
							latch.countDown();
						}
					}
				});
			}
			try {
				int totalTimeout = ((providerSize % threads == 0) ? (providerSize / threads)
						: ((providerSize / threads) + 1)) * timeout + 500;
				latch.await(totalTimeout, TimeUnit.MILLISECONDS); // 一直等到
			} catch (InterruptedException e) {
				LOGGER.error("Exception when close transport", e);
			} finally {
				closepool.shutdown();
			}
		}
	}

	/**
	 * 获取当前的Provider全部列表（包括连上和没连上的）
	 *
	 * @return 当前的Provider列表
	 */
	public Set<Endpoint> currentProviderList() {
		return connectionHolder.currentProviderList();
	}

	/**
	 * 获取当前的Provider列表（每种状态已分开）
	 *
	 * @return 当前的Provider列表
	 */
	public Map<String, Set<Endpoint>> currentProviderMap() {
		return connectionHolder.currentProviderMap();
	}
	
	 /**
     * Endpoint对象得到 ClientTransportConfig
     *
     * @param endpoint
     *         endpoint
     * @return ClientEndpointConfig
     */
    private ClientEndpointConfig endpointToClientConfig(Endpoint endpoint,ClientEndpointConfig config) {
    	ClientEndpointConfig temp = (ClientEndpointConfig)config.clone();
    	temp.setProvider(endpoint);
        return temp;
    }
}