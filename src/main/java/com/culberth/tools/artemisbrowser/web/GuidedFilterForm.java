package com.culberth.tools.artemisbrowser.web;

import com.culberth.tools.artemisbrowser.filter.GuidedFilter;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * The filter builder's fields, bound from a GET like any other search form so a built search has a URL.
 *
 * <p>
 * A mutable bean because Spring binds indexed rows ({@code conditions[0].name}) into one. It is only a carrier:
 * {@link GuidedFilter} checks every value. Rows past {@link #ROWS} are ignored, whatever the query string says.
 */
public class GuidedFilterForm
{

    public static final int ROWS = 3;

    private List<ConditionRow> conditions = new ArrayList<>();
    private String priorityMin = "";
    private String priorityMax = "";
    private String sentFrom = "";
    private String sentTo = "";
    private String zone = ZoneId.systemDefault().getId();
    private String durability = "ANY";

    public GuidedFilterForm()
    {
        for (int i = 0; i < ROWS; i++)
        {
            conditions.add(new ConditionRow());
        }
    }

    public GuidedFilter.Request toRequest()
    {
        List<GuidedFilter.Condition> rows = conditions.stream().limit(ROWS)
                .map(row -> new GuidedFilter.Condition(row.getName(), row.getType(), row.getOperator(), row.getValue()))
                .toList();
        return new GuidedFilter.Request(rows, priorityMin, priorityMax, sentFrom, sentTo, zone, durability);
    }

    /** For the template: the rows to draw, never more than {@link #ROWS} and never fewer. */
    public List<ConditionRow> getRows()
    {
        List<ConditionRow> rows = new ArrayList<>(conditions.stream().limit(ROWS).toList());
        while (rows.size() < ROWS)
        {
            rows.add(new ConditionRow());
        }
        return rows;
    }

    public GuidedFilter.Type[] getTypes()
    {
        return GuidedFilter.Type.values();
    }

    public GuidedFilter.Operator[] getOperators()
    {
        return GuidedFilter.Operator.values();
    }

    public String getServerZone()
    {
        return ZoneId.systemDefault().getId();
    }

    public List<ConditionRow> getConditions()
    {
        return conditions;
    }

    public void setConditions(List<ConditionRow> conditions)
    {
        this.conditions = conditions == null ? new ArrayList<>() : conditions;
    }

    public String getPriorityMin()
    {
        return priorityMin;
    }

    public void setPriorityMin(String priorityMin)
    {
        this.priorityMin = priorityMin;
    }

    public String getPriorityMax()
    {
        return priorityMax;
    }

    public void setPriorityMax(String priorityMax)
    {
        this.priorityMax = priorityMax;
    }

    public String getSentFrom()
    {
        return sentFrom;
    }

    public void setSentFrom(String sentFrom)
    {
        this.sentFrom = sentFrom;
    }

    public String getSentTo()
    {
        return sentTo;
    }

    public void setSentTo(String sentTo)
    {
        this.sentTo = sentTo;
    }

    public String getZone()
    {
        return zone;
    }

    public void setZone(String zone)
    {
        this.zone = zone;
    }

    public String getDurability()
    {
        return durability;
    }

    public void setDurability(String durability)
    {
        this.durability = durability;
    }

    /** One property condition row. */
    public static class ConditionRow
    {

        private String name = "";
        private String type = "STRING";
        private String operator = "EQUALS";
        private String value = "";

        public String getName()
        {
            return name;
        }

        public void setName(String name)
        {
            this.name = name;
        }

        public String getType()
        {
            return type;
        }

        public void setType(String type)
        {
            this.type = type;
        }

        public String getOperator()
        {
            return operator;
        }

        public void setOperator(String operator)
        {
            this.operator = operator;
        }

        public String getValue()
        {
            return value;
        }

        public void setValue(String value)
        {
            this.value = value;
        }
    }
}
