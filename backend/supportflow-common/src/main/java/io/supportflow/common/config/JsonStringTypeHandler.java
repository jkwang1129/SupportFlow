package io.supportflow.common.config;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;

/**
 * JSON / JSONB 列的类型处理器：实体里放 JSON 字符串，数据库列是 Postgres 的 {@code json} / {@code jsonb}。
 *
 * <p><b>不挂它会出什么事</b>：MyBatis 默认按 {@code varchar} 绑定参数，而 Postgres **不会隐式转换**，
 * 直接报错——</p>
 * <pre>
 * ERROR: column "config_json" is of type jsonb but expression is of type character varying
 * 建议：You will need to rewrite or cast the expression.
 * </pre>
 * <p>这类错误的表现往往很隐蔽：接口返回 200，但数据一行都没写进去（或者按租户批量时"每个租户都跳过"）。
 * 用 {@code Types.OTHER} 把参数交给 PG **按目标列的类型**解析即可；读取时按字符串取出，语义与写入前一致。</p>
 *
 * <p><b>为什么放在 common</b>：customer / ops / billing 三个服务都要往 json 列写 JSON 字符串，
 * 之前各写了一份（只有"空值怎么办"这点差异），容易改一处漏一处。统一到这里，谁要用谁挂。</p>
 *
 * <p><b>空值策略</b>：空串不是合法 JSON（PG 会报 {@code invalid input syntax for type json}），
 * 所以空串统一按 <b>NULL</b> 写——"没有内容"在 json 列里本来就该是 NULL，而且列如果不允许 NULL，
 * 会**立刻报约束冲突**（响亮的错误，好过悄悄写进去一个空对象）。
 * 用法上注意：{@code NOT NULL} 的 json 列别传空串（本仓库里那几个写 json 的服务都是序列化之后再写，
 * 最少也是 {@code {}}，所以不会走到这一步）。</p>
 *
 * <p>用法：实体字段上挂 {@code @TableField(value = "xxx_json", typeHandler = JsonStringTypeHandler.class)}，
 * 同时把实体类上的 {@code @TableName} 设为 {@code autoResultMap = true}（查询时才会走这个处理器）。</p>
 */
public class JsonStringTypeHandler extends BaseTypeHandler<String> {

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, String parameter, JdbcType jdbcType)
            throws SQLException {
        String text = parameter == null ? "" : parameter.trim();
        if (text.isEmpty()) {
            ps.setObject(i, null, Types.OTHER);
            return;
        }
        ps.setObject(i, text, Types.OTHER);
    }

    @Override
    public String getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return rs.getString(columnName);
    }

    @Override
    public String getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return rs.getString(columnIndex);
    }

    @Override
    public String getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return cs.getString(columnIndex);
    }
}
