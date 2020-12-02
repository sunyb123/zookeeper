package com.lagou.db.listener;

import com.alibaba.druid.pool.DruidDataSource;
import com.alibaba.druid.pool.DruidPooledConnection;
import org.I0Itec.zkclient.IZkDataListener;
import org.I0Itec.zkclient.ZkClient;

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.sql.DataSource;
import java.sql.Driver;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

public class DbInit implements ServletContextListener {
    DruidPooledConnection connection;
    ZkClient zkClient;

    public void contextDestroyed(ServletContextEvent arg0) {
        System.out.println("==关闭==");

    }

    public void contextInitialized(ServletContextEvent arg0) {

        zkClient = new ZkClient("localhost:2181",5000);
        zkClient.subscribeDataChanges("/jdbc_conf", new IZkDataListener() {
            @Override
            public void handleDataChange(String s, Object o) throws Exception {
                connection.close();
                Map<String,String> map = (Map<String, String>) o;
                init(map);
            }

            @Override
            public void handleDataDeleted(String s) throws Exception {

            }
        });


        Map<String,String> map = zkClient.readData("/jdbc_conf");
        init(map);
    }

    //创建数据库连接
    private void init(Map<String,String> map){

        System.out.println("==初始化数据库连接==");
        DruidDataSource dataSource = new DruidDataSource();
        dataSource.setUrl(map.get("url"));
        dataSource.setUsername(map.get("userName"));
        dataSource.setPassword(map.get("password"));
        Driver driver = null;
        try {
            driver = new com.mysql.cj.jdbc.Driver();
        } catch (SQLException throwables) {
            throwables.printStackTrace();
        }
        dataSource.setDriver(driver);

        PreparedStatement pst = null;
        try {
            connection = dataSource.getConnection();
            pst = connection.prepareStatement( "SELECT id FROM user;" );
            ResultSet rs = pst.executeQuery();
            while (rs.next()) {

                System.out.println("id : " + rs.getString(1));
            }
        } catch (SQLException throwables) {
            throwables.printStackTrace();
        }

    }
}
