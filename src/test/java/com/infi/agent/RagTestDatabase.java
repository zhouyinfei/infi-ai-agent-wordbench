package com.infi.agent;

import javax.sql.DataSource;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.springframework.core.io.ClassPathResource;

/** 创建真实 MyBatis 会话，供独立持久化与基础设施测试使用。 */
final class RagTestDatabase {
    private RagTestDatabase() {
    }

    static SqlSession open(DataSource dataSource) throws Exception {
        Configuration configuration = new Configuration(new Environment("rag-test", new JdbcTransactionFactory(), dataSource));
        configuration.setMapUnderscoreToCamelCase(true);
        try (var input = new ClassPathResource("mapper/KnowledgeDocumentMapper.xml").getInputStream()) {
            new XMLMapperBuilder(input, configuration, "mapper/KnowledgeDocumentMapper.xml", configuration.getSqlFragments()).parse();
        }
        return new SqlSessionFactoryBuilder().build(configuration).openSession(true);
    }
}
