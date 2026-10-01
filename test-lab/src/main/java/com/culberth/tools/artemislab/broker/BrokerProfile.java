package com.culberth.tools.artemislab.broker;

import java.util.List;

/**
 * How a lab broker is configured at startup, for conditions a running broker cannot be talked into. Each non-standard
 * profile replaces the image's entrypoint with one that does what the image's does — create the instance once, then run
 * it — with its edits to the freshly created configuration in between. Creating only when {@code etc/broker.xml} is
 * missing keeps a restart from recreating, or re-editing, anything.
 *
 * <p>
 * The extra users are test accounts on a disposable loopback broker, named for what they may do; their passwords are
 * their names and are shown on the lab page so a person can sign Artemis Browser in as them.
 */
public enum BrokerProfile
{

    STANDARD("Standard", "The image's own configuration.", List.of(), List.of()),

    /**
     * Per-operation management permissions, which exist only with {@code management-message-rbac} — read at startup
     * only. User {@code viewer} may manage but not read the acceptors, disk usage, diverts, prepared transactions,
     * roles, or any address attribute; user {@code nomanage} may connect and not manage at all. The same edits as
     * Artemis Browser's {@code PartialAvailabilityIT}.
     */
    RESTRICTED("Restricted users",
            "management-message-rbac on; viewer (some reads denied) and nomanage (no management) besides the admin.",
            List.of("viewer", "nomanage"),
            List.of("printf '\\nviewer = viewer\\nnomanage = nomanage\\n' >> etc/artemis-users.properties",
                    "printf '\\nviewer = viewer\\nnomanage = nomanage\\n' >> etc/artemis-roles.properties",
                    "sed -i 's#roles=\"amq\"#roles=\"amq,viewer,nomanage\"#g;"
                            + " s#type=\"manage\" roles=\"amq,viewer,nomanage\"#type=\"manage\" roles=\"amq,viewer\"#;"
                            + " s#<security-settings>#<management-message-rbac>true</management-message-rbac>"
                            + "<security-settings><security-setting match=\"mops.\\#\">"
                            + "<permission type=\"view\" roles=\"amq,viewer\"/><permission type=\"edit\" roles=\"amq\"/>"
                            + "</security-setting>" + denied("mops.broker.getAcceptorsAsJSON")
                            + denied("mops.broker.getDiskStoreUsage") + denied("mops.broker.getDivertNames")
                            + denied("mops.broker.listPreparedTransactions") + denied("mops.broker.getRolesAsJSON")
                            + denied("mops.address.\\#") + "#' etc/broker.xml")),

    /** A 2MB global memory limit instead of half the heap, so bounded traffic shows real memory pressure (P04). */
    LOW_MEMORY("Low global memory", "global-max-size 2MB, so a little traffic is a large share of it.", List.of(),
            List.of("sed -i 's#<max-disk-usage>90</max-disk-usage>#<max-disk-usage>90</max-disk-usage>"
                    + "<global-max-size>2097152</global-max-size>#' etc/broker.xml")),

    /**
     * {@code max-disk-usage} of 1%: any real disk is already past it, so the broker reports its store full without
     * anything being written to fill it (P04).
     */
    DISK_FULL("Disk threshold reached",
            "max-disk-usage 1%, already exceeded: the store counts as full, nothing filled.", List.of(),
            List.of("sed -i 's#<max-disk-usage>90</max-disk-usage>#<max-disk-usage>1</max-disk-usage>#' etc/broker.xml"));

    private final String label;
    private final String description;
    private final List<String> users;
    private final List<String> edits;

    BrokerProfile(String label, String description, List<String> users, List<String> edits)
    {
        this.label = label;
        this.description = description;
        this.users = users;
        this.edits = edits;
    }

    public String label()
    {
        return label;
    }

    public String description()
    {
        return description;
    }

    /** Extra test users; each one's password is its name. */
    public List<String> users()
    {
        return users;
    }

    public boolean standard()
    {
        return this == STANDARD;
    }

    /** The replacement entrypoint, or null for the image's own. */
    public String entrypoint()
    {
        if (standard())
        {
            return null;
        }
        return String
                .join("\n", "set -e", "cd /var/lib/artemis-instance", "if [ ! -f etc/broker.xml ]; then",
                        "/opt/artemis/bin/artemis create --user \"$ARTEMIS_USER\" --password \"$ARTEMIS_PASSWORD\""
                                + " --silent --require-login .",
                        String.join("\n", edits), "fi", "exec ./bin/artemis run", "");
    }

    private static String denied(String match)
    {
        return "<security-setting match=\"" + match + "\"><permission type=\"view\" roles=\"amq\"/></security-setting>";
    }
}
