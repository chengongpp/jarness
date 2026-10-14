package ch.predmet.jarness.core.mod;

import ch.predmet.jarness.api.mod.DependencyKind;
import ch.predmet.jarness.api.mod.ModDependency;
import ch.predmet.jarness.api.mod.ModDescriptor;
import ch.predmet.jarness.api.mod.ModOrder;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for the FancyModLoader-style dependency resolver. */
class ModResolverTest {

    private static ModResolver.Candidate mod(String id, ModDependency... deps) {
        return new ModResolver.Candidate(
                new ModDescriptor(id, id, "1.0", "", "com.example." + id, List.of(deps), false, false),
                Path.of(id + ".jar"));
    }

    private static ModResolver.Candidate system(String id) {
        return new ModResolver.Candidate(ModDescriptor.system(id, id, "1.0", "com.example." + id), null);
    }

    @Test
    void dependencyOrderIsRespected() {
        ModResolver.Result result = ModResolver.resolve(List.of(
                mod("b", new ModDependency("a", "*", DependencyKind.REQUIRED, ModOrder.AFTER, null)),
                mod("a")));
        assertTrue(result.ok(), () -> result.errors().toString());
        assertEquals(List.of("a", "b"),
                result.order().stream().map(c -> c.descriptor().id()).toList());
    }

    @Test
    void systemModsComeFirstOnTies() {
        ModResolver.Result result = ModResolver.resolve(List.of(mod("aaa"), system("zzz")));
        assertEquals(List.of("zzz", "aaa"),
                result.order().stream().map(c -> c.descriptor().id()).toList());
    }

    @Test
    void missingRequiredDependencyIsAnError() {
        ModResolver.Result result = ModResolver.resolve(List.of(mod("a", ModDependency.required("ghost"))));
        assertEquals(1, result.errors().size());
        assertTrue(result.errors().get(0).message().contains("ghost"));
    }

    @Test
    void missingOptionalDependencyIsFine() {
        ModResolver.Result result = ModResolver.resolve(List.of(mod("a", ModDependency.optional("ghost"))));
        assertTrue(result.ok(), () -> result.errors().toString());
    }

    @Test
    void incompatiblePresentIsAnError() {
        ModResolver.Result result = ModResolver.resolve(List.of(
                mod("a", new ModDependency("b", "*", DependencyKind.INCOMPATIBLE, ModOrder.NONE, null)),
                mod("b")));
        assertEquals(1, result.errors().size());
        assertTrue(result.errors().get(0).message().contains("b"));
    }

    @Test
    void versionRangeMismatchIsAnError() {
        ModResolver.Result result = ModResolver.resolve(List.of(
                mod("a", new ModDependency("b", "[2.0,3.0)", DependencyKind.REQUIRED, ModOrder.NONE, null)),
                mod("b")));
        assertEquals(1, result.errors().size());
    }

    @Test
    void duplicateIdsAreAnError() {
        ModResolver.Result result = ModResolver.resolve(List.of(mod("a"), mod("a")));
        assertEquals(1, result.errors().size());
        assertTrue(result.errors().get(0).message().toLowerCase().contains("duplicat"));
    }

    @Test
    void cyclesAreReported() {
        ModResolver.Result result = ModResolver.resolve(List.of(
                mod("a", ModDependency.required("b")),
                mod("b", ModDependency.required("a"))));
        assertEquals(1, result.errors().size());
        assertTrue(result.errors().get(0).message().toLowerCase().contains("cycle"));
    }
}
