package com.lagou.handler;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import org.springframework.beans.BeansException;
import org.springframework.cglib.reflect.FastClass;
import org.springframework.cglib.reflect.FastMethod;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Component;
import service.RpcRequest;

import java.lang.reflect.InvocationTargetException;

@Component
public class UserServerHandler extends ChannelInboundHandlerAdapter implements ApplicationContextAware {




    private static ApplicationContext applicationContext2;

    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {

        UserServerHandler.applicationContext2 = applicationContext;
    }



    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {


        RpcRequest msg1 = (RpcRequest) msg;
        Object handler = handler(msg1);
        ctx.writeAndFlush("success");



        // 判断是否符合约定，符合则调用本地方法，返回数据
        // msg:  UserService#sayHello#are you ok?
//        if(msg.toString().startsWith("UserService")){
//            UserServiceImpl userService = new UserServiceImpl();
//            String result = userService.sayHello(msg.toString().substring(msg.toString().lastIndexOf("#") + 1));
//            ctx.writeAndFlush(result);
//        }


    }

    private Object handler(RpcRequest request) throws ClassNotFoundException, InvocationTargetException {

        //使用Class.forName进行加载Class文件
        Class<?> clazz = Class.forName(request.getClassName());
        Object serviceBean = applicationContext2.getBean(clazz);

        Class<?> serviceClass = serviceBean.getClass();

        String methodName = request.getMethodName();

        Class<?>[] parameterTypes = request.getParameterTypes();
        Object[] parameters = request.getParameters();


        FastClass fastClass = FastClass.create(serviceClass);
        FastMethod fastMethod = fastClass.getMethod(methodName, parameterTypes);

        return fastMethod.invoke(serviceBean, parameters);
    }



}
