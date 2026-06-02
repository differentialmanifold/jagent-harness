package io.github.differentialmanifold.jagentharness.example.order;

import java.util.Collections;
import java.util.List;

import io.github.differentialmanifold.jagentharness.core.agent.AgentContext;
import io.github.differentialmanifold.jagentharness.core.prompt.SkillDescriptor;
import io.github.differentialmanifold.jagentharness.core.prompt.SkillProvider;
import org.springframework.stereotype.Component;

@Component
public class OrderSkillProvider implements SkillProvider {

    @Override
    public List<SkillDescriptor> listSkills(AgentContext context) {
        return Collections.singletonList(new SkillDescriptor(
                "订单状态查询",
                "当用户询问订单状态时，先调用 order_query 获取订单状态，再用业务术语解释下一步。",
                "business://skills/order-status"));
    }
}
