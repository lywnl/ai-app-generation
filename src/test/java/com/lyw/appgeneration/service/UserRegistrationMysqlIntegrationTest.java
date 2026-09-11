package com.lyw.appgeneration.service;

import com.lyw.appgeneration.exception.BusinessException;
import com.lyw.appgeneration.mapper.UserMapper;
import com.lyw.appgeneration.model.entity.User;
import com.lyw.appgeneration.service.impl.UserServiceImpl;
import com.mybatisflex.core.datasource.FlexDataSource;
import com.mybatisflex.core.mybatis.FlexConfiguration;
import com.mybatisflex.core.mybatis.FlexSqlSessionFactoryBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.Connection;
import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "USER_IDENTITY_MYSQL_URL", matches = "jdbc:mysql:.*")
class UserRegistrationMysqlIntegrationTest {
    private Connection connection;
    private SqlSession session;
    private UserMapper mapper;
    private UserServiceImpl users;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection(System.getenv("USER_IDENTITY_MYSQL_URL"),
                System.getenv("USER_IDENTITY_MYSQL_USER"), System.getenv("USER_IDENTITY_MYSQL_PASSWORD"));
        // 同连接临时表复制真实约束并遮蔽原表，测试数据不会进入业务用户表。
        try (var statement = connection.createStatement()) {
            String createTable;
            try (var result = statement.executeQuery("SHOW CREATE TABLE `user`")) {
                assertTrue(result.next());
                createTable = result.getString(2);
            }
            assertTrue(createTable.startsWith("CREATE TABLE `user`"));
            statement.execute(createTable.replaceFirst("CREATE TABLE", "CREATE TEMPORARY TABLE"));
        }
        var dataSource = new SingleConnectionDataSource(connection, true);
        dataSource.setUrl(System.getenv("USER_IDENTITY_MYSQL_URL"));
        var configuration = new FlexConfiguration(new Environment("user-registration-test",
                new JdbcTransactionFactory(), new FlexDataSource("user-registration-test", dataSource)));
        var factory = new FlexSqlSessionFactoryBuilder().build(configuration);
        configuration.addMapper(UserMapper.class);
        session = factory.openSession(true);
        mapper = session.getMapper(UserMapper.class);
        users = new UserServiceImpl();
        ReflectionTestUtils.setField(users, "userMapper", mapper);
        ReflectionTestUtils.setField(users, "displayIdentityService", new UserDisplayIdentityService(mapper));
    }

    @AfterEach
    void tearDown() throws Exception {
        try {
            if (session != null) {
                session.close();
            }
        } finally {
            if (connection != null) {
                connection.close();
            }
        }
    }

    @Test
    void registrationUsesDatabaseDefaultsAndCanLogIn() {
        Long id = users.userRegister("registration-fixture", "fixture-password", "fixture-password");
        assertNotNull(id);
        User saved = mapper.selectOneById(id);
        assertNotNull(saved);
        assertIdentityAndDefaults(saved);
        assertEquals("registration-fixture", saved.getUserAccount());
        assertEquals(users.getEncryptPassword("fixture-password"), saved.getUserPassword());
        assertEquals("user", saved.getUserRole());
        assertEquals(id, users.userLogin("registration-fixture", "fixture-password",
                new MockHttpServletRequest()).getId());
        assertThrows(BusinessException.class,
                () -> users.userRegister("registration-fixture", "fixture-password", "fixture-password"));
    }

    @Test
    void adminCreationPreservesRoleAndUsesDatabaseDefaults() {
        User user = User.builder().userAccount("admin-fixture").userPassword("fixture-hash")
                .userRole("admin").userName("旧昵称").userAvatar("old-avatar").build();
        assertTrue(users.createUser(user));
        assertNotNull(user.getId());
        User saved = mapper.selectOneById(user.getId());
        assertIdentityAndDefaults(saved);
        assertEquals("admin", saved.getUserRole());
        assertEquals("fixture-hash", saved.getUserPassword());
    }

    private void assertIdentityAndDefaults(User user) {
        assertTrue(user.getUserName().matches("用户_[0-9]{8}"));
        assertEquals(UserDisplayIdentityService.DEFAULT_AVATAR, user.getUserAvatar());
        assertNotNull(user.getEditTime());
        assertNotNull(user.getCreateTime());
        assertNotNull(user.getUpdateTime());
        assertEquals(0, user.getIsDelete());
    }
}
