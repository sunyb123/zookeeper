package com.lagou.db;

import org.I0Itec.zkclient.ZkClient;

import java.util.HashMap;
import java.util.Map;

public class SetJdbcConf {

    public static void main(String[] args) {
        ZkClient zkClient = new ZkClient("localhost:2181",5000);
        if(!zkClient.exists("/jdbc_conf")){
            zkClient.createPersistent("/jdbc_conf",true);
        }
        Map<String,String> map = new HashMap<>();
        map.put("url","jdbc:mysql://localhost:3306/test?serverTimezone=GMT");
        map.put("userName","root");
        map.put("password","root");
        zkClient.writeData("/jdbc_conf",map);

    }
}
