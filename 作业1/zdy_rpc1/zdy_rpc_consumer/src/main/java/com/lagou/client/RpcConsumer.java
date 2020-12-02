package com.lagou.client;

import com.lagou.service.JSONSerializer;
import com.lagou.service.RpcEncoder;
import com.lagou.service.RpcRequest;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.string.StringDecoder;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooKeeper;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class RpcConsumer {

    //创建线程池对象
    private static ExecutorService executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());

    //UserClientHandler列表 key:url
    private static Map<String,UserClientHandler> userClientHandlers = new HashMap<>();
    //服务端列表key:nodeName vel:nodeData
    private static Map<String,String> serverNodes = new HashMap<>();
    //netty连接列表 key:url
    private static Map<String,ChannelFuture> channelFutureMap = new HashMap<>();

    private static ZooKeeper zooKeeper;
    //通过zookeeper获取服务列表
    public RpcConsumer(String hostName,int port){
        /**
         客户端可以通过创建⼀个zk实例来连接zk服务器
         new Zookeeper(connectString,sesssionTimeOut,Wather)
         connectString: 连接地址：IP：端⼝
         sesssionTimeOut：会话超时时间：单位毫秒
         Wather：监听器(当特定事件触发监听时，zk会通过watcher通知到客户端)
         */
        try {
            zooKeeper = new ZooKeeper(hostName + ":" + String.valueOf(port), 5000, new Watcher() {
                @Override
                public void process(WatchedEvent watchedEvent) {
                    //成功创建链接
                    if(watchedEvent.getState() == Event.KeeperState.SyncConnected){
                        //获取服务节点列表
                        try {
                            List<String> nodes =  zooKeeper.getChildren("/Service",true);
                            if(nodes!=null&&!nodes.isEmpty()){
                                for(String node:nodes){
                                    byte[] data = zooKeeper.getData("/Service/"+node,false,null);
                                    String connectUrl = new String(data,"utf-8");
                                    serverNodes.put(node,connectUrl);
                                    String[] strings =connectUrl.split(":");
                                    if(strings.length==2){
                                        //初始化netty链接
                                        initClient(strings[0],Integer.parseInt(strings[1]));
                                    }
                                }
                            }else{
                                System.out.println("没有获取到服务节点");
                            }
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }

                    //服务端注册数据变化
                    if(watchedEvent.getType() == Event.EventType.NodeChildrenChanged){
                        try {
                            List<String> nodes =  zooKeeper.getChildren(watchedEvent.getPath(),true);
                            if(nodes!=null&&!nodes.isEmpty()){
                                for(String node:nodes){
                                    if(!serverNodes.keySet().contains(node)){
                                        //新建服务器链接
                                        byte[] data = zooKeeper.getData("/Service/"+node,false,null);
                                        String connectUrl = new String(data,"utf-8");
                                        serverNodes.put(node,connectUrl);
                                        String[] strings =connectUrl.split(":");
                                        if(strings.length==2){
                                            //初始化netty链接
                                            initClient(strings[0],Integer.parseInt(strings[1]));
                                        }
                                    }
                                }
                            }
                            for(String node:serverNodes.keySet()){
                                if(!nodes.contains(node)){
                                    //删除服务器链接
                                    System.out.println("删除服务器链接");
                                    userClientHandlers.remove(serverNodes.get(node));
                                    channelFutureMap.get(serverNodes.get(node)).channel().closeFuture().sync();
                                    channelFutureMap.remove(serverNodes.get(node));
                                    serverNodes.remove(node);
                                }
                            }
                        }catch (Exception e){

                        }
                    }
                }
            });
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    //1.创建一个代理对象 providerName：UserService#sayHello are you ok?
    public Object createProxy(final Class<?> serviceClass){
        //借助JDK动态代理生成代理对象
        return  Proxy.newProxyInstance(Thread.currentThread().getContextClassLoader(), new Class<?>[]{serviceClass}, new InvocationHandler() {
            public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                //（1）调用初始化netty客户端的方法

//                if(userClientHandler == null){
//                 initClient();
//                }

                             //封装
                RpcRequest request = new RpcRequest();
                String requestId = UUID.randomUUID().toString();
                System.out.println(requestId);

                String className = method.getDeclaringClass().getName();
                String methodName = method.getName();

                Class<?>[] parameterTypes = method.getParameterTypes();

                request.setRequestId(requestId);
                request.setClassName(className);
                request.setMethodName(methodName);
                request.setParameterTypes(parameterTypes);
                request.setParameters(args);



                // 设置参数
                if(userClientHandlers.size()!=0){
                    Random random = new Random();
//                    int s = random.nextInt(userClientHandlers.size()-1)%(userClientHandlers.size()-1-0+1) + 0;
                    UserClientHandler userClientHandler = (UserClientHandler) userClientHandlers.values().toArray()[0];
                    userClientHandler.setPara(request);
                    System.out.println(request);
                    System.out.println("设置参数完成");

                    // 去服务端请求数据

                    return executor.submit(userClientHandler).get();
                }
                return "调用失败";
            }
        });


    }



    //2.初始化netty客户端
    public static  void initClient(String host,int port) throws InterruptedException {
        UserClientHandler userClientHandler = new UserClientHandler();
        userClientHandlers.put(host+":"+String.valueOf(port),userClientHandler);

        EventLoopGroup group = new NioEventLoopGroup();

        Bootstrap bootstrap = new Bootstrap();
        bootstrap.group(group)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.TCP_NODELAY,true)
                .handler(new ChannelInitializer<SocketChannel>() {
                    protected void initChannel(SocketChannel ch) throws Exception {
                        ChannelPipeline pipeline = ch.pipeline();
                        pipeline.addLast(new RpcEncoder(RpcRequest.class, new JSONSerializer()));
                        pipeline.addLast(new StringDecoder());
                        pipeline.addLast(userClientHandler);
                    }
                });

        ChannelFuture channelFuture = bootstrap.connect(host,port).sync();
        channelFutureMap.put(host+":"+String.valueOf(port),channelFuture);
        System.out.println("创建服务链接，服务数量："+userClientHandlers.size());

    }


}
