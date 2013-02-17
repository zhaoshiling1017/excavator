package com.googlecode.excavator.consumer;

import static com.googlecode.excavator.PropertyConfiger.getAppName;
import static com.googlecode.excavator.advice.Advices.doAfter;
import static com.googlecode.excavator.advice.Advices.doBefores;
import static com.googlecode.excavator.advice.Advices.doFinally;
import static com.googlecode.excavator.advice.Advices.doThrow;
import static com.googlecode.excavator.advice.Direction.Type.CONSUMER;
import static com.googlecode.excavator.protocol.RmiResponse.RESULT_CODE_FAILED_BIZ_THREAD_POOL_OVERFLOW;
import static com.googlecode.excavator.protocol.RmiResponse.RESULT_CODE_FAILED_SERVICE_NOT_FOUND;
import static com.googlecode.excavator.protocol.RmiResponse.RESULT_CODE_FAILED_TIMEOUT;
import static com.googlecode.excavator.protocol.RmiResponse.RESULT_CODE_SUCCESSED_RETURN;
import static com.googlecode.excavator.protocol.RmiResponse.RESULT_CODE_SUCCESSED_THROWABLE;
import static com.googlecode.excavator.util.ExceptionUtil.hasNetworkException;
import static com.googlecode.excavator.util.SerializerUtil.changeToSerializable;
import static com.googlecode.excavator.util.SerializerUtil.isSerializableType;
import static com.googlecode.excavator.util.SignatureUtil.signature;
import static com.googlecode.excavator.util.TimeoutUtil.getFixTimeout;
import static java.lang.String.format;
import static java.lang.System.currentTimeMillis;
import static org.apache.commons.lang.StringUtils.isBlank;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.commons.lang.ArrayUtils;
import org.apache.log4j.Logger;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFuture;

import com.googlecode.excavator.Runtimes;
import com.googlecode.excavator.constant.Log4jConstant;
import com.googlecode.excavator.consumer.message.SubscribeServiceMessage;
import com.googlecode.excavator.consumer.support.ConsumerSupport;
import com.googlecode.excavator.exception.ProviderNotFoundException;
import com.googlecode.excavator.exception.ServiceNotFoundException;
import com.googlecode.excavator.exception.ThreadPoolOverflowException;
import com.googlecode.excavator.exception.UnknowCodeException;
import com.googlecode.excavator.message.Messages;
import com.googlecode.excavator.protocol.RmiRequest;
import com.googlecode.excavator.protocol.RmiResponse;

/**
 * �����ߴ�������
 * @author vlinux
 *
 */
public class ConsumerProxyFactory {

	private final Logger networkLog = Logger.getLogger(Log4jConstant.NETWORK);
	private final Logger agentLog = Logger.getLogger(Log4jConstant.AGENT);
	
	private ConsumerSupport support;
	
	private ConsumerProxyFactory() throws Exception {
		support = new ConsumerSupport();
		support.init();
		Runtime.getRuntime().addShutdownHook(new Thread(){
			@Override
			public void run() {
				try {
					support.destroy();
				} catch (Exception e) {
					agentLog.warn("destory consumer support failed.", e);
				}
			}
		});
	}

	/**
	 * ���ɿͻ��˴�������
	 * @param targetInterface
	 * @param group
	 * @param version
	 * @param defaultTimeout
	 * @param methodTimeoutMap
	 * @return
	 */
	private InvocationHandler createConsumerProxyHandler(final Class<?> targetInterface, final String group, final String version, final long defaultTimeout, final Map<String, Long> methodTimeoutMap ) {
		return new InvocationHandler() {

			@Override
			public Object invoke(Object proxy, Method method, Object[] args)
					throws Throwable {
				
				final String sign = signature(method);
				final long timeout = getFixTimeout(method, defaultTimeout, methodTimeoutMap);
				final RmiRequest req = generateRmiRequest(group, version, args, sign, timeout);
				final Receiver.Wrapper receiverWrapper = registerRecevier(req);
				
				final ReentrantLock lock = receiverWrapper.getLock();
				final long start = currentTimeMillis();
				final ChannelRing.Wrapper channelWrapper;
				Runtimes.Runtime runtime = null;
				lock.lock();
				try {
					try {
						channelWrapper = takeChannelRingWrapper(req);
						
						// ��channelע�뵽����channel��
						receiverWrapper.setChannel(channelWrapper.getChannel());
						
						// ��������ʱ����
						runtime = new Runtimes.Runtime(req, channelWrapper.getProvider(), 
								targetInterface, method,
								(InetSocketAddress)channelWrapper.getChannel().getLocalAddress(), 
								(InetSocketAddress)channelWrapper.getChannel().getRemoteAddress());
						doBefores(CONSUMER, runtime);
						waitForWrite(req, channelWrapper);
					} catch(Throwable t) {
						support.unRegister(req.getId());
						throw t;
					}
					
					final long leftTimeout = timeout - (currentTimeMillis() - start);
					if( leftTimeout <= 0 ) {
						throw new TimeoutException(format("request:%s is waiting for write ready, but timeout:%dms", req, timeout));
					}
					waitForReceive(receiverWrapper, req, leftTimeout);
					
					Object returnObj = getReturnObject(receiverWrapper, channelWrapper);
					doAfter(CONSUMER, runtime, returnObj, currentTimeMillis() - start/*cost*/);
					return returnObj;
				} catch(Throwable t){
					if( null == runtime ) {
						runtime = new Runtimes.Runtime(req,targetInterface,method);
					}
					doThrow(CONSUMER, runtime, t);
					throw t;
				} finally {
					lock.unlock();
					doFinally(CONSUMER, runtime);
				}
				
			}

			/**
			 * ע�������
			 * @param req
			 * @return
			 * @throws ProviderNotFoundException
			 */
			private Receiver.Wrapper registerRecevier(final RmiRequest req)
					throws ProviderNotFoundException {
				final Receiver.Wrapper receiverWrapper = support.register(req);
				// û�յ�receiverWrapper˵�����񲻴���
				if( null == receiverWrapper ) {
					throw new ProviderNotFoundException(format("provider not found. req:%s", req));
				}
				return receiverWrapper;
			}

			/**
			 * ����req����
			 * @param group
			 * @param version
			 * @param args
			 * @param sign
			 * @param timeout
			 * @return
			 */
			private RmiRequest generateRmiRequest(final String group,
					final String version, Object[] args, final String sign,
					final long timeout) {
				final RmiRequest req;
				
				final Runtimes.Runtime runtime = Runtimes.getRuntime();
				
				// ����ǵ�һ��������Ҫ��������token
				if( null == runtime ) {
					req = new RmiRequest(
							group, version, sign,
							changeToSerializable(args), getAppName(), timeout);
				} 
				
				// ������ǵ�һ����������Ҫ��ԭ����token����
				else {
					req = new RmiRequest(
							runtime.getReq().getToken(),
							group, version, sign,
							changeToSerializable(args), getAppName(), timeout);
				}
				return req;
			}
			
		};
	}
	
	/**
	 * ��������
	 * @param targetInterface
	 * @param group
	 * @param version
	 * @param defaultTimeout
	 * @param methodTimeoutMap
	 * @return
	 * @throws Exception
	 */
	@SuppressWarnings("unchecked")
	public <T> T proxy(Class<T> targetInterface, String group, String version, long defaultTimeout, Map<String, Long> methodTimeoutMap) throws Exception {
		
		// ������
		check(targetInterface, group, version);

		// ���Ѷ˴�������
		final InvocationHandler consumerProxyHandler = createConsumerProxyHandler(targetInterface, group, version, defaultTimeout, methodTimeoutMap);
		
		// ����Ŀ���������
		Object proxyObject = Proxy.newProxyInstance(
				Thread.currentThread().getContextClassLoader(), 
				new Class<?>[] {targetInterface}, consumerProxyHandler);

		// ע�����
		registerService(targetInterface, group, version, defaultTimeout, methodTimeoutMap);
		
		return (T)proxyObject;
		
	}
	

	/**
	 * ��ȡchannelRing�İ�װ<br/>
	 * �����Ͼ��ǻ�ȡһ�����õ�channel��
	 * @param req
	 * @return
	 * @throws Throwable
	 */
	private ChannelRing.Wrapper takeChannelRingWrapper(RmiRequest req) throws Throwable {
		ChannelRing.Wrapper channelWrapper = support.ring(req);
		if( null == channelWrapper ) {
			throw new ProviderNotFoundException(
					format("provider not found. req:%s", req));
		}
		return channelWrapper;
	}
	
	/**
	 * д����
	 * @param reqEvt
	 * @param channelWrapper
	 * @throws Throwable
	 */
	private void waitForWrite(RmiRequest req, ChannelRing.Wrapper channelWrapper) throws Throwable {
		ChannelFuture future = channelWrapper.getChannel().write(req);
		future.awaitUninterruptibly();
		if( !future.isSuccess() ) {
			Throwable cause = future.getCause();
			if( hasNetworkException(cause) ) {
				channelWrapper.setMaybeDown(true);
			}
			networkLog.warn(format("write req:%s failed. maybeDown:%s", req, channelWrapper.isMaybeDown()), future.getCause());
			throw future.getCause();
		}
	}
	
	/**
	 * �Ⱥ�ظ�֪ͨ
	 * @oaram wrapper
	 * @param req
	 * @param leftTimeout
	 * @return
	 * @throws TimeoutException
	 * @throws ProviderNotFoundException
	 * @throws InterruptedException 
	 */
	private Receiver.Wrapper waitForReceive(Receiver.Wrapper wrapper, RmiRequest req, long leftTimeout) 
			throws TimeoutException, ProviderNotFoundException, InterruptedException {
		
		// �ȴ���Ϣ
		wrapper.getWaitResp().await(leftTimeout, TimeUnit.MILLISECONDS);
		
		// û�յ�response�¼�˵���ǳ�ʱ
		if( null == wrapper.getResponse() ) {
			throw new TimeoutException(format("req:%s timeout:%dms", req, req.getTimeout()));
		}
		
		return wrapper;
		
	}
	
	/**
	 * ��ȡreturn�Ķ���
	 * @param receiverWrapper
	 * @param channelWrapper
	 * @return
	 * @throws Throwable
	 */
	private Object getReturnObject(Receiver.Wrapper receiverWrapper, ChannelRing.Wrapper channelWrapper) throws Throwable {
		final RmiRequest req = receiverWrapper.getRequest();
		final RmiResponse resp = receiverWrapper.getResponse();
		final Channel channel = channelWrapper.getChannel();
		switch(resp.getCode()) {
		case RESULT_CODE_FAILED_TIMEOUT:
			// ���յ�response�ˣ�����response��֪�Ѿ���ʱ
			throw new TimeoutException(
					format("received response, but response report provider:%s was timeout. req:%s;resp:%s;",
							channel.getRemoteAddress(), req, resp));
		case RESULT_CODE_FAILED_BIZ_THREAD_POOL_OVERFLOW:
			// ���յ�response�ˣ�����response��֪�����̳߳���
			throw new ThreadPoolOverflowException(
					format("received response, but response report provider:%s was overflow. req:%s;resp:%s;",
							channel.getRemoteAddress(), req, resp));
		case RESULT_CODE_FAILED_SERVICE_NOT_FOUND:
			// ���յ�response�ˣ�����response��֪�����Ҳ���
			throw new ServiceNotFoundException(
					format("received response, but response report provider:%s was not found the method. req:%s;resp:%s;",
							channel.getRemoteAddress(), req, resp));
		case RESULT_CODE_SUCCESSED_RETURN:
			// ���յ�response�ˣ�response����������return����ʽ����
			return resp.getObject();
		case RESULT_CODE_SUCCESSED_THROWABLE:
			// ���յ�response�ˣ�response�������������쳣����ʽ����
			throw (Throwable)resp.getObject();
		default:
			// ���յ�response�ˣ����ǲ�֪��response���ص�״̬��
			throw new UnknowCodeException(
					format("received response, but response's code is illegal. provider:%s;req:%s;resp:%s;",
							channel.getRemoteAddress(), req, resp));
		}//case
	}

	/**
	 * ����У��
	 * @param targetInterface
	 * @param group
	 * @param version
	 */
	private void check(Class<?> targetInterface, String group, String version) {
		
		// ������У��
		if( isBlank(version) ) {
			throw new IllegalArgumentException("version is blank");
		}
		if( isBlank(group) ) {
			throw new IllegalArgumentException("group is blank");
		}
		if( null == targetInterface ) {
			throw new IllegalArgumentException("targetInterface is null");
		}
		
		// ��鴫�ݽ����Ľӿڷ����У��Ƿ�����в������л��Ķ�������
		Method[] methods = targetInterface.getMethods();
		if( null != methods ) {
			for( Method method : methods ) {
				if( !isSerializableType(method.getReturnType()) ) {
					throw new IllegalArgumentException("method returnType is not serializable");
				}
				if( !isSerializableType(method.getParameterTypes()) ) {
					throw new IllegalArgumentException("method parameter is not serializable");
				}
			}//for
		}//if
		
	}
	
	/**
	 * ע�����
	 */
	private void registerService(Class<?> targetInterface, String group, String version, long defaultTimeout, Map<String, Long> methodTimeoutMap) {
		final Method[] methods = targetInterface.getMethods();
		if( ArrayUtils.isEmpty(methods) ) {
			return;
		}
		for(Method method : methods) {
			final long timeout = getFixTimeout(method, defaultTimeout, methodTimeoutMap);
			final String sign = signature(method);
			final ConsumerService service = new ConsumerService(group, version, sign, timeout);
			Messages.post(new SubscribeServiceMessage(service));
		}
	}
	
	
	private static volatile ConsumerProxyFactory singleton;
	
	/**
	 * ����
	 * @return
	 * @throws Exception 
	 */
	public static ConsumerProxyFactory singleton() throws Exception {
		if( null == singleton ) {
			synchronized(ConsumerProxyFactory.class) {
				if( null == singleton ) {
					singleton = new ConsumerProxyFactory();
				}
			}
		}
		return singleton;
	}

}