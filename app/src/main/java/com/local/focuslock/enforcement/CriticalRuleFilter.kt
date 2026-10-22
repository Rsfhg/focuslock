package com.local.focuslock.enforcement
import com.local.focuslock.domain.Rule
import com.local.focuslock.data.CriticalApps
class CriticalRuleFilter(private val ownPackage: String) {
    private var critical: Set<String>? = null
    fun update(packages: Set<String>) { critical = packages }
    fun filter(rules: List<Rule>): Map<String, Rule> {
        val excluded = critical ?: return emptyMap()
        return rules.filter { it.packageName !in excluded && CriticalApps.allowed(it.packageName, ownPackage, emptySet(), null) }.associateBy { it.packageName }
    }
}
