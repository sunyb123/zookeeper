package client;


import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.string.StringDecoder;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.data.Stat;
import service.JSONSerializer;
import service.RpcEncoder;
import service.RpcRequest;

import java.io.IOException;
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
    private static BiMap<String,UserClientHandler> userClientHandlers = HashBiMap.create();
    //服务端列表key:nodeName vel:nodeData
    private static Map<String,String> serverNodes = new HashMap<>();
    //netty连接列表 key:nodeName
    private static Map<String,ChannelFuture> channelFutureMap = new HashMap<>();

    //服务端响应时间列表 key:nodeName
    private static Map<String,Long> timeMap = new HashMap<>();

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
                                    if(strings.length>=2){
                                        //初始化netty链接
                                        long time = Integer.MAX_VALUE;
                                        if(strings.length>2){
                                            time = Long.valueOf(strings[2]);
                                        }
                                        initClient(strings[0],Integer.parseInt(strings[1]),node,time);
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
                                        if(strings.length>=2){
                                            //初始化netty链接
                                            long time = Integer.MAX_VALUE;
                                            if(strings.length>2){
                                                time = Long.valueOf(strings[2]);
                                            }
                                            initClient(strings[0],Integer.parseInt(strings[1]),node,time);
                                        }
                                    }
                                }
                            }
                            for(String node:serverNodes.keySet()){
                                if(!nodes.contains(node)){
                                    //删除服务器链接
                                    System.out.println("删除服务器链接");
                                    timeMap.remove(userClientHandlers.get(node));
                                    userClientHandlers.remove(node);
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

        //定时5秒上报响应时间
        Timer timer = new Timer();
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                System.out.println("上报响应时间");
                try {
                    if(!timeMap.isEmpty()){
                        for(String node:timeMap.keySet()){
//                            Stat stat = zooKeeper.exists("/Service/"+node,false);
//                            if(stat!=null){
                                byte[] data = zooKeeper.getData("/Service/"+node,false,null);
                                String dataStr = new String(data);
                                String newData;
                                if(dataStr.split(":").length>2){
                                    newData = dataStr.substring(0,dataStr.lastIndexOf(":"))+timeMap.get(node);
                                }else{
                                    newData = dataStr+":"+timeMap.get(node);
                                }
                                zooKeeper.setData("/Service/"+node,newData.getBytes(),-1);
//                            }
                        }
                    }
                }catch (Exception e){
                    System.out.println(e.getMessage());
                }
            }
        },5000,5000);
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
                    //循环比较找到响应时间最小的userClientHandler
                    long time = Integer.MAX_VALUE;
                    String nodeName = "";
                    for(String node:timeMap.keySet()){
                        if(timeMap.get(node)<=time){
                            nodeName = node;
                            time = timeMap.get(node);
                        }
                    }
                    UserClientHandler userClientHandler = userClientHandlers.get(nodeName);
                    Random random = new Random();
                    userClientHandler.setPara(request);
                    System.out.println(request);
                    System.out.println("设置参数完成");

                    // 去服务端请求数据
                    long startTime = new Date().getTime();
                    Object o = executor.submit(userClientHandler).get();
                    //取得响应时间
                    long countTime = new Date().getTime() - startTime;
                    timeMap.put(userClientHandlers.inverse().get(userClientHandler),countTime);
                    return o;
                }
                return "调用失败";
            }
        });


    }



    //2.初始化netty客户端
    public static  void initClient(String host,int port,String nodeName,long time) throws InterruptedException {
        UserClientHandler userClientHandler = new UserClientHandler();
        userClientHandlers.put(nodeName,userClientHandler);
        timeMap.put(nodeName,time);
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
