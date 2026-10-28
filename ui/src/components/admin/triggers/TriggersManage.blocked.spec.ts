import {afterEach, beforeEach, expect, it, vi} from "vitest"
import {flushPromises, VueWrapper} from "@vue/test-utils"
import {createPinia} from "pinia"
import {createMemoryHistory, createRouter} from "vue-router"
import {i18nMount} from "../../../../tests/unit/i18nMount"

vi.mock("@kestra-io/kestra-sdk/logs", () => ({searchLogs: vi.fn().mockResolvedValue({results: [], total: 0}), deleteLogsFromFlow: vi.fn()}))
vi.mock("override/stores/auth", () => ({
    useAuthStore: () => ({user: {isAllowed: () => false, hasAny: () => false, hasAnyAction: () => false}}),
}))
vi.mock("../../../stores/flow", () => ({useFlowStore: () => ({})}))
vi.mock("../../../stores/executions", () => ({useExecutionsStore: () => ({})}))
vi.mock("../../../utils/triggers", () => ({
    searchTriggers: vi.fn().mockResolvedValue({
        total: 3,
        results: [
            {flowId: "by-policy", blocked: {reason: "POLICY", policyIds: ["deny-log"], message: "Log plugin is not allowed by policy"}},
            {flowId: "check-failed", blocked: {reason: "CHECK_FAILED", policyIds: [], message: "Governance policy resolution failed: connection reset"}},
            {flowId: "accepted", nextEvaluationDate: "2026-10-27T10:01:00Z"},
        ].map(({flowId, ...state}) => ({
            trigger: {id: "schedule", type: "io.kestra.plugin.core.trigger.Schedule"},
            state: {namespace: "company.team", flowId, triggerId: "schedule", ...state},
        })),
    }),
}))

import TriggersManage from "./TriggersManage.vue"

beforeEach(() => {
    vi.useFakeTimers()
    localStorage.clear()
    sessionStorage.clear()
})

afterEach(() => {
    localStorage.clear()
    sessionStorage.clear()
    document.title = ""
    vi.useRealTimers()
})

const blockedTooltips = (wrapper: VueWrapper) =>
    wrapper.findAllComponents({name: "KsTooltip"}).filter(tooltip => tooltip.find("[data-test=\"trigger-blocked\"]").exists())

// kestra-ee#10290: the admin Triggers page shows a governance block in place of the next evaluation date.
it("shows a block only on the blocked triggers, hiding the cause of a failed check and saying it may linger", async () => {
    localStorage.setItem("columns_displayTriggersColumns", "flowId,nextEvaluationDate")
    const router = createRouter({
        history: createMemoryHistory(),
        routes: [{name: "admin/triggers", path: "/:tenant/triggers", component: {template: "<div/>"}}],
    })
    await router.push({name: "admin/triggers", params: {tenant: "main"}})
    await router.isReady()
    const wrapper = i18nMount(TriggersManage, {
        messages: {"trigger blocked tooltip": "{reason} (until next evaluation)"},
        global: {
            plugins: [createPinia(), router],
            stubs: {FlowRun: true, TopNavBar: true, AddTriggerModal: true, TestEventDialog: true},
        },
    })
    await flushPromises()

    expect(wrapper.findAll("[data-test=\"trigger-blocked\"]")).toHaveLength(2)
    expect(blockedTooltips(wrapper).map(tooltip => tooltip.props("content"))).toEqual([
        "Log plugin is not allowed by policy (until next evaluation)",
        "trigger blocked check failed (until next evaluation)",
    ])
})
