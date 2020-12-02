package com.lagou.service;

import com.lagou.handler.UserServerHandler;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.string.StringEncoder;
import org.apache.zookeeper.*;
import org.apache.zookeeper.data.Stat;
import org.springframework.stereotype.Service;
import service.JSONSerializer;
import service.RpcDecoder;
import service.RpcRequest;
import service.UserService;

import java.io.IOException;

@Service
public class UserServiceImpl implements UserService {

    public String sayHello(String word) {
        System.out.println("调用成功--参数 "+word);
        return "调用成功--参数 "+word;
    }

    private static ZooKeeper zooKeeper;

    //hostName:ip地址  port:端口号
    public static void startServer(String hostName,int port) throws InterruptedException {

        NioEventLoopGroup bossGroup = new NioEventLoopGroup();
        NioEventLoopGroup workerGroup = new NioEventLoopGroup();

        ServerBootstrap serverBootstrap = new ServerBootstrap();
        serverBootstrap.group(bossGroup,workerGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    protected void initChannel(SocketChannel ch) throws Exception {
                        ChannelPipeline pipeline = ch.pipeline();
                        pipeline.addLast(new StringEncoder());
                        pipeline.addLast(new RpcDecoder(RpcRequest.class, new JSONSerializer()));
                        pipeline.addLast(new UserServerHandler());

                    }
                });
        serverBootstrap.bind(hostName,port).sync();
        System.out.println("服务启动");
        //发布zookeeper服务
        release(hostName,port);


    }

    private static void release(String hostName,int port){
        /**
         客户端可以通过创建⼀个zk实例来连接zk服务器
         new Zookeeper(connectString,sesssionTimeOut,Wather)
         connectString: 连接地址：IP：端⼝
         sesssionTimeOut：会话超时时间：单位毫秒
         Wather：监听器(当特定事件触发监听时，zk会通过watcher通知到客户端)
         */
        try {
            zooKeeper = new ZooKeeper("localhost:2181", 5000, new Watcher() {
                @Override
                public void process(WatchedEvent watchedEvent) {
                    //建立链接
                    if(watchedEvent.getState() == Event.KeeperState.SyncConnected){
                        //创建临时节点
                        /**
                         * path ：节点创建的路径
                         * data[] ：节点创建要保存的数据，是个byte类型的
                         * acl ：节点创建的权限信息(4种类型)
                         * ANYONE_ID_UNSAFE : 表示任何⼈
                         * AUTH_IDS ：此ID仅可⽤于设置ACL。它将被客户机验证的ID替
                         换。
                         * OPEN_ACL_UNSAFE ：这是⼀个完全开放的ACL(常⽤)-->
                         world:anyone
                         * CREATOR_ALL_ACL ：此ACL授予创建者身份验证ID的所有权限
                         * createMode ：创建节点的类型(4种类型)
                         * PERSISTENT：持久节点
                         * PERSISTENT_SEQUENTIAL：持久顺序节点
                         * EPHEMERAL：临时节点
                         * EPHEMERAL_SEQUENTIAL：临时顺序节点
                         String node = zookeeper.create(path,data,acl,createMode);
                         */
                        try {
                            Stat exists = zooKeeper.exists("/Service",false);
                            if(exists==null){
                                String node = zooKeeper.create("/Service","Service".getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE,CreateMode.PERSISTENT);
                            }
                            String node = zooKeeper.create("/Service/UserService",(hostName+":"+String.valueOf(port)).getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE,CreateMode.EPHEMERAL_SEQUENTIAL);
                            System.out.println("发布服务成功，节点名称："+node);
                        } catch (Exception e) {
                            e.printStackTrace();
                            System.out.println("发布失败");
                        }
                    }
                }
            });
        } catch (IOException e) {
            e.printStackTrace();
            System.out.println("发布失败");
        }
    }




}
