package net.corda.loadtest

import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigParseOptions
import net.corda.loadtest.tests.crossCashTest
import net.corda.loadtest.tests.selfIssueTest
import net.corda.nodeapi.config.parseAs
import java.io.File

/**
 * This is how load testing works:
 *
 * Setup:
 *   The load test assumes that there is an active SSH Agent running with an added identity it can use to connect to all
 *   remote nodes. To run intellij with the ssh-agent:
 *     $ ssh-agent $SHELL
 *     $ ssh-add
 *     $ exec idea.sh # 'exec' is required so we can detach from the surrounding shell without quiting the agent.
 *
 *   In order to make our life easier we download the remote node certificates to localhost and use those directly. The
 *   reasoning being that if somebody has SSH access to the nodes they have access to the certificates anyway.
 *   TODO Still, is this ok? Perhaps we should include a warning in the docs.
 *   We then tunnel the remote Artemis messaging ports to localhost and establish an RPC link.
 *
 * Running the tests:
 *   The [LoadTest] API assumes that each load test will keep generating some kind of work to push to the nodes. It also
 *   assumes that the nodes' behaviour will be somewhat predictable, which is tracked in a state. For example say we
 *   want to self issue Cash on each node. We can predict that if we submit an Issue request of
 *   100 USD and 200 USD we should end up with 300 USD issued by the node. Each load test can define its own such
 *   invariant and should check for it in [LoadTest.gatherRemoteState].
 *   We then simply keep generating pieces of work and check that the invariants hold(see [LoadTest.RunParameters] on
 *   how to configure the generation).
 *   In addition for each test a number of disruptions may be specified that make the nodes' jobs harder. Each
 *   disruption is basically an infinite loop of wait->mess something up->repeat. Invariants should hold under these
 *   conditions as well.
 *
 * Diagnostic:
 *   TODO currently the diagnostic is quite poor, all we can say is that the predicted state is different from the real
 *   one, or that some piece of work failed to execute in some state. Logs need to be checked manually.
 *
 * TODO: Any load test that involves intra-node transactions will currently fail because the node re-picks the same states
 * if tx creation requests arrive quickly, which result in notarisation failures. So this needs figuring out before we
 * can run the load tests properly.
 */

fun main(args: Array<String>) {
    if (args.isEmpty()) {
        throw IllegalArgumentException("Usage: <binary> PATH_TO_CONFIG")
    }
    val options = ConfigParseOptions.defaults().setAllowMissing(false)
    val defaultConfig = ConfigFactory.parseResources("loadtest-reference.conf", options)
    val defaultSshUserConfig = ConfigFactory.parseMap(
            if (defaultConfig.hasPath("sshUser")) emptyMap() else mapOf("sshUser" to System.getProperty("user.name"))
    )
    val customConfig = ConfigFactory.parseFile(File(args[0]), options)
    val resolvedConfig = customConfig.withFallback(defaultConfig).withFallback(defaultSshUserConfig).resolve()
    val loadTestConfiguration = resolvedConfig.parseAs<LoadTestConfiguration>()

    require(loadTestConfiguration.nodeHosts.isNotEmpty()) { "Please specify at least one node host" }

    runLoadTests(loadTestConfiguration, listOf(
            selfIssueTest to LoadTest.RunParameters(
                    parallelism = 100,
                    generateCount = 10000,
                    clearDatabaseBeforeRun = false,
                    gatherFrequency = 1000,
                    disruptionPatterns = listOf(
                            listOf(), // no disruptions
                            listOf(
                                    DisruptionSpec(
                                            disruption = hang(2000L..4000L),
                                            nodeFilter = { true },
                                            noDisruptionWindowMs = 500L..1000L
                                    ),
                                    DisruptionSpec(
                                            disruption = kill,
                                            nodeFilter = isNetworkMap.or(isNotary),
                                            noDisruptionWindowMs = 10000L..20000L // Takes a while for it to restart
                                    ),
                                    // DOCS START 1
                                    DisruptionSpec(
                                            disruption = strainCpu(parallelism = 4, durationSeconds = 10),
                                            nodeFilter = { true },
                                            noDisruptionWindowMs = 5000L..10000L
                                    )
                                    // DOCS END 1
                            )
                    )
            ),
            crossCashTest to LoadTest.RunParameters(
                    parallelism = 4,
                    generateCount = 2000,
                    clearDatabaseBeforeRun = false,
                    gatherFrequency = 10,
                    disruptionPatterns = listOf(
                            listOf(),
                            listOf(
                                    DisruptionSpec(
                                            disruption = hang(2000L..4000L),
                                            nodeFilter = { true },
                                            noDisruptionWindowMs = 500L..1000L
                                    ),
                                    DisruptionSpec(
                                            disruption = kill,
                                            nodeFilter = isNetworkMap.or(isNotary),
                                            noDisruptionWindowMs = 10000L..20000L // Takes a while for it to restart
                                    ),
                                    DisruptionSpec(
                                            disruption = strainCpu(parallelism = 4, durationSeconds = 10),
                                            nodeFilter = { true },
                                            noDisruptionWindowMs = 5000L..10000L
                                    )
                            )
                    )
            )
    ))
}
