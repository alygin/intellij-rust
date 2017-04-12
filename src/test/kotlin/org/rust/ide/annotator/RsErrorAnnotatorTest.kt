package org.rust.ide.annotator

class RsErrorAnnotatorTest : RsAnnotatorTestBase() {
    override val dataPath = "org/rust/ide/annotator/fixtures/errors"

    fun testInvalidModuleDeclarations() = doTest("helper.rs")

    fun testCreateFileQuickFix() = checkByDirectory {
        openFileInEditor("mod.rs")
        applyQuickFix("Create module file")
    }

    fun testCreateFileAndExpandModuleQuickFix() = checkByDirectory {
        openFileInEditor("foo.rs")
        applyQuickFix("Create module file")
    }

    fun testPaths() = checkErrors("""
        fn main() {
            let ok = self::super::super::foo;
            let ok = super::foo::bar;

            let _ = <error descr="Invalid path: self and super are allowed only at the beginning">::self</error>::foo;
            let _ = <error>::super</error>::foo;
            let _ = <error>self::self</error>;
            let _ = <error>super::self</error>;
            let _ = <error>foo::self</error>::bar;
            let _ = <error>self::foo::super</error>::bar;
        }
    """)

    fun testConstFree() = checkErrors("""
        const FOO: u32 = 42;
        pub const PUB_FOO: u32 = 41;
        static S_FOO: bool = true;
        static mut S_MUT_FOO: bool = false;
        pub static S_PUB_BAR: u8 = 0;
        pub static mut S_PUB_MUT_BAR: f16 = 1.12;

        <error descr="Constant `BAR` must have a value">const BAR: u8;</error>
        <error descr="Static constant `DEF_BAR` cannot have the `default` qualifier">default</error> static DEF_BAR: u16 = 9;
    """)

    fun testConstInTrait() = checkErrors("""
        trait Foo {
            const FOO_1: u16 = 10;
            const FOO_2: f64;

            <error descr="Constant `PUB_BAZ` cannot have the `pub` qualifier">pub</error> const PUB_BAZ: bool;
            <error descr="Constant `DEF_BAR` cannot have the `default` qualifier">default</error> const DEF_BAR: u16 = 9;
            <error descr="Static constants are not allowed in traits">static</error> ST_FOO: u32 = 18;
        }
    """)

    fun testConstInImpl() = checkErrors("""
        struct Foo;
        impl Foo {
            const FOO: u32 = 109;
            pub const PUB_FOO: u32 = 81;
            default const DEF_FOO: u8 = 1;

            <error descr="Constant `BAR` must have a value">const BAR: u8;</error>
            <error descr="Static constants are not allowed in impl blocks">static</error> ST_FOO: u32 = 18;
        }
    """)

    fun testConstInExtern() = checkErrors("""
        extern "C" {
            static mut FOO: u32;
            pub static mut PUB_FOO: u8;
            static NON_MUT_FOO: u32;

            <error descr="Static constant `DEF_FOO` cannot have the `default` qualifier">default</error> static mut DEF_FOO: bool;
            <error descr="Only static constants are allowed in extern blocks">const</error> CONST_FOO: u32;
            static mut VAL_FOO: u32 <error descr="Static constants in extern blocks cannot have values">= 10</error>;
        }
    """)

    fun testFunction() = checkErrors("""
        #[inline]
        pub const unsafe fn full<'a, T>(id: u32, name: &'a str, data: &T, _: &mut FnMut(Display)) -> Option<u32> where T: Sized {
            None
        }
        fn trailing_comma(a: u32,) {}
        extern "C" fn ext_fn() {}

        <error descr="Function `foo_default` cannot have the `default` qualifier">default</error> fn foo_default(f: u32) {}
        fn ref_self(<error descr="Function `ref_self` cannot have `self` parameter">&mut self</error>, f: u32) {}
        fn no_body()<error descr="Function `no_body` must have a body">;</error>
        fn anon_param(<error descr="Function `anon_param` cannot have anonymous parameters">u8</error>, a: i16) {}
        fn var_foo(a: bool, <error descr="Function `var_foo` cannot be variadic">...</error>) {}
        <error>default</error> fn two_errors(<error>u8</error>, a: i16) {}
    """)

    fun testImplAssocFunction() = checkErrors("""
        struct Person<D> { data: D }
        impl<D> Person<D> {
            #[inline]
            pub const unsafe fn new<'a>(id: u32, name: &'a str, data: D, _: bool) -> Person<D> where D: Sized {
                Person { data: data }
            }
            default fn def() {}
            extern "C" fn ext_fn() {}

            default <error descr="Default associated function `def_pub` cannot have the `pub` qualifier">pub</error> fn def_pub() {}
            fn no_body()<error descr="Associated function `no_body` must have a body">;</error>
            fn anon_param(<error descr="Associated function `anon_param` cannot have anonymous parameters">u8</error>, a: i16) {}
            fn var_foo(a: bool, <error descr="Associated function `var_foo` cannot be variadic">...</error>) {}
        }
    """)

    fun testImplMethod() = checkErrors("""
        struct Person<D> { data: D }
        impl<D> Person<D> {
            #[inline]
            pub const unsafe fn check<'a>(&self, s: &'a str) -> bool where D: Sized {
                false
            }
            default fn def(&self) {}
            extern "C" fn ext_m(&self) {}

            default <error descr="Default method `def_pub` cannot have the `pub` qualifier">pub</error> fn def_pub(&self) {}
            fn no_body(&self)<error descr="Method `no_body` must have a body">;</error>
            fn anon_param(&self, <error descr="Method `anon_param` cannot have anonymous parameters">u8</error>, a: i16) {}
            fn var_foo(&self, a: bool, <error descr="Method `var_foo` cannot be variadic">...</error>) {}
        }
    """)

    fun testTraitAssocFunction() = checkErrors("""
        trait Animal<T> {
            #[inline]
            unsafe fn feed<'a>(food: T, d: &'a str, _: bool, f32) -> Option<f64> where T: Sized {
                None
            }
            fn no_body();
            extern "C" fn ext_fn();

            <error descr="Trait function `default_foo` cannot have the `default` qualifier">default</error> fn default_foo();
            <error descr="Trait function `pub_foo` cannot have the `pub` qualifier">pub</error> fn pub_foo();
            fn tup_param(<error descr="Trait function `tup_param` cannot have tuple parameters">(x, y): (u8, u8)</error>, a: bool);
            fn var_foo(a: bool, <error descr="Trait function `var_foo` cannot be variadic">...</error>);
        }
    """)

    fun testTraitMethod() = checkErrors("""
        trait Animal<T> {
            #[inline]
            fn feed<'a>(&mut self, food: T, d: &'a str, _: bool, f32) -> Option<f64> where T: Sized {
                None
            }
            fn no_body(self);
            extern "C" fn ext_m();

            <error descr="Trait method `default_foo` cannot have the `default` qualifier">default</error> fn default_foo(&self);
            <error descr="Trait method `pub_foo` cannot have the `pub` qualifier">pub</error> fn pub_foo(&mut self);
            fn tup_param(&self, <error descr="Trait method `tup_param` cannot have tuple parameters">(x, y): (u8, u8)</error>, a: bool);
            fn var_foo(&self, a: bool, <error descr="Trait method `var_foo` cannot be variadic">...</error>);
        }
    """)

    fun testForeignFunction() = checkErrors("""
        extern {
            #[cold]
            pub fn full(len: size_t, ...) -> size_t;

            <error descr="Foreign function `default_foo` cannot have the `default` qualifier">default</error> fn default_foo();
            <error descr="Foreign function `with_const` cannot have the `const` qualifier">const</error> fn with_const();
            <error descr="Foreign function `with_unsafe` cannot have the `unsafe` qualifier">unsafe</error> fn with_unsafe();
            <error descr="Foreign function `with_ext_abi` cannot have an extern ABI">extern "C"</error> fn with_ext_abi();
            fn with_self(<error descr="Foreign function `with_self` cannot have `self` parameter">&self</error>, s: size_t);
            fn anon_param(<error descr="Foreign function `anon_param` cannot have anonymous parameters">u8</error>, a: i8);
            fn with_body() <error descr="Foreign function `with_body` cannot have a body">{ let _ = 1; }</error>
            fn var_coma(a: size_t, ...<error descr="`...` must be last in argument list for variadic function">,</error>);
        }
    """)

    fun testUnionTuple() = checkErrors("""
        union U<error descr="Union cannot be tuple-like">(i32, f32)</error>;
    """)

    fun testTypeAliasFree() = checkErrors("""
        type Int = i32;
        pub type UInt = u32;
        type Maybe<T> = Option<T>;
        type SizedMaybe<T> where T: Sized = Option<T>;

        <error descr="Type `DefBool` cannot have the `default` qualifier">default</error> type DefBool = bool;
        <error descr="Aliased type must be provided for type `Unknown`">type Unknown;</error>
        type Show<error descr="Type `Show` cannot have type parameter bounds">: Display</error> = u32;
    """)

    fun testTypeAliasInTrait() = checkErrors("""
        trait Computer {
            type Int;
            type Long = i64;
            type Show: Display;

            <error descr="Type `DefSize` cannot have the `default` qualifier">default</error> type DefSize = isize;
            <error descr="Type `PubType` cannot have the `pub` qualifier">pub</error> type PubType;
            type GenType<error descr="Type `GenType` cannot have generic parameters"><T></error> = Option<T>;
            type WhereType <error descr="Type `WhereType` cannot have `where` clause">where T: Sized</error> = f64;
        }
    """)

    fun testTypeAliasInTraitImpl() = checkErrors("""
            trait Vehicle {
                type Engine;
                type Control;
                type Lock;
                type Cage;
                type Insurance;
                type Driver;
            }
            struct NumericVehicle<T> { foo: T }
            impl<T> Vehicle for NumericVehicle<T> {
                type Engine = u32;
                default type Control = isize;
                type Lock<error descr="Type `Lock` cannot have generic parameters"><T></error> = Option<T>;
                type Cage<error descr="Type `Cage` cannot have type parameter bounds">: Sized</error> = f64;
                type Insurance <error descr="Type `Insurance` cannot have `where` clause">where T: Sized</error> = i8;
                <error descr="Aliased type must be provided for type `Driver`">type Driver;</error>
            }
    """)

    fun testInvalidChainComparison() = checkErrors("""
        fn foo(x: i32) {
            <error descr="Chained comparison operator require parentheses">1 < x < 3</error>;
            <error descr="Chained comparison operator require parentheses">1 > x < 3</error>;
            <error descr="Chained comparison operator require parentheses">1 > x > 3</error>;
            <error descr="Chained comparison operator require parentheses">1 < x > 3</error>;
            <error descr="Chained comparison operator require parentheses">1 <= x < 3</error>;
            <error descr="Chained comparison operator require parentheses">1 < x <= 3</error>;
            <error descr="Chained comparison operator require parentheses">1 == x < 3</error>;
            <error descr="Chained comparison operator require parentheses">1 < x == 3</error>;
        }
    """)

    fun testValidChainComparison() = checkErrors("""
        fn foo(x: i32, y: bool) {
            let _ = 1 < x && x < 10;
            let _ = 1 < x || x < 10;
            let _ = (1 == x) == y;
            let _ = y == (1 == x);
        }
    """)

    fun testE0046_AbsentMethodInTraitImpl() = checkErrors("""
        trait TError {
            fn bar();
            fn baz();
            fn boo();
        }
        <error descr="Not all trait items implemented, missing: `bar`, `boo` [E0046]">impl TError for ()</error> {
            fn baz() {}
        }
    """)

    fun testE0046_NotApplied() = checkErrors("""
        trait T {
            fn foo() {}
            fn bar();
        }
        impl T for() {
            fn bar() {}
        }
    """)

    fun testE0050_IncorrectParamsNumberInTraitImpl() = checkErrors("""
        trait T {
            fn ok_foo();
            fn ok_bar(a: u32, b: f64);
            fn foo();
            fn bar(a: u32);
            fn baz(a: u32, b: bool, c: f64);
            fn boo(&self, o: isize);
        }
        struct S;
        impl T for S {
            fn ok_foo() {}
            fn ok_bar(a: u32, b: f64) {}
            fn foo<error descr="Method `foo` has 1 parameter but the declaration in trait `T` has 0 [E0050]">(a: u32)</error> {}
            fn bar<error descr="Method `bar` has 2 parameters but the declaration in trait `T` has 1 [E0050]">(a: u32, b: bool)</error> {}
            fn baz<error descr="Method `baz` has 0 parameters but the declaration in trait `T` has 3 [E0050]">()</error> {}
            fn boo<error descr="Method `boo` has 2 parameters but the declaration in trait `T` has 1 [E0050]">(&self, o: isize, x: f16)</error> {}
        }
    """)

    fun testE0061_InvalidParametersNumberInFreeFunctions() = checkErrors("""
        fn par_0() {}
        fn par_1(p: bool) {}
        fn par_3(p1: u32, p2: f64, p3: &'static str) {}

        fn main() {
            par_0();
            par_1(true);
            par_3(12, 7.1, "cool");

            par_0<error descr="This function takes 0 parameters but 1 parameter was supplied [E0061]">(4)</error>;
            par_1<error descr="This function takes 1 parameter but 0 parameters were supplied [E0061]">()</error>;
            par_1<error descr="This function takes 1 parameter but 2 parameters were supplied [E0061]">(true, false)</error>;
            par_3<error descr="This function takes 3 parameters but 2 parameters were supplied [E0061]">(5, 1.0)</error>;
        }
    """)

    fun testE0061_InvalidParametersNumberInAssocFunction() = checkErrors("""
        struct Foo;
        impl Foo {
            fn par_0() {}
            fn par_2(p1: u32, p2: f64) {}
        }

        fn main() {
            Foo::par_0();
            Foo::par_2(12, 7.1);

            Foo::par_0<error descr="This function takes 0 parameters but 1 parameter was supplied [E0061]">(4)</error>;
            Foo::par_2<error descr="This function takes 2 parameters but 3 parameters were supplied [E0061]">(5, 1.0, "three")</error>;
        }
    """)

    fun testE0061_InvalidParametersNumberInImplMethods() = checkErrors("""
        struct Foo;
        impl Foo {
            fn par_0(&self) {}
            fn par_2(&self, p1: u32, p2: f64) {}
        }

        fn main() {
            let foo = Foo;
            foo.par_0();
            foo.par_2(12, 7.1);

            foo.par_0<error descr="This function takes 0 parameters but 1 parameter was supplied [E0061]">(4)</error>;
            foo.par_2<error descr="This function takes 2 parameters but 3 parameters were supplied [E0061]">(5, 1.0, "three")</error>;
            foo.par_2<error descr="This function takes 2 parameters but 0 parameters were supplied [E0061]">()</error>;
        }
    """)

    fun testE0061_InvalidParametersNumberInTupleStructs() = checkErrors("""
        struct Foo0();
        struct Foo1(u8);
        fn main() {
            let _ = Foo0();
            let _ = Foo1(1);

            let _ = Foo0<error descr="This function takes 0 parameters but 1 parameter was supplied [E0061]">(4)</error>;
            let _ = Foo1<error descr="This function takes 1 parameter but 2 parameters were supplied [E0061]">(10, false)</error>;
        }
    """)

    fun testE0061_InvalidParametersNumberInTupleEnumVariants() = checkErrors("""
        enum Foo {
            VAR0(),
            VAR1(u8)
        }
        fn main() {
            let _ = Foo::VAR0();
            let _ = Foo::VAR1(1);

            let _ = Foo::VAR0<error descr="This function takes 0 parameters but 1 parameter was supplied [E0061]">(4)</error>;
            let _ = Foo::VAR1<error descr="This function takes 1 parameter but 2 parameters were supplied [E0061]">(10, false)</error>;
        }
    """)

    fun testE0061_RespectsCfgAttribute() = checkErrors("""
        struct Foo;
        impl Foo {
            #[cfg(windows)]
            fn bar(&self, p1: u32) {}
            #[cfg(not(windows))]
            fn bar(&self) {}
        }
        fn main() {
            let foo = Foo;
            foo.bar(10);  // Ignore both calls
            foo.bar();
        }
    """)

    // We would like to cover such cases, but the resolve engine has some flaws at the moment,
    // so just ignore trait implementations to remove false positives
    fun testE0061_IgnoresTraitImplementations() = checkErrors("""
        trait Foo1 { fn foo(&self); }
        trait Foo2 { fn foo(&self, a: u8); }
        struct Bar;
        impl Foo1 for Bar {
            fn foo(&self) {}
        }
        impl<T> Foo2 for Box<T> {
            fn foo(&self, a: u8) {}
        }
        type BFoo1<'a> = Box<Foo1 + 'a>;

        fn main() {
            let bar: BFoo1 = Box::new(Bar);
            bar.foo();   // Resolves to Foo2.foo() for Box<T>, though Foo1.foo() for Bar is the correct one
        }
    """)

    fun `test E0069 empty return`() = checkErrors("""
        fn ok1() { return; }
        fn ok2() -> () { return; }
        fn ok3() -> u32 {
            let _ = || return;
            return 10
        }

        fn err1() -> bool {
            <error descr="`return;` in a function whose return type is not `()` [E0069]">return</error>;
        }
        fn err2() -> ! {
            <error>return</error>
        }
    """)

    fun `test E0106 missing lifetime in struct field`() = checkErrors("""
        struct Foo<'a> {
            a: &'a str,
            b: (bool, (u8, &'a f64)),
            c: Option<Box<&'a u32>>,
            f: &'a Fn (&u32) -> &u32,
        }
        struct Bar<'a> {
            o: &'a str,
            a: <error descr="Missing lifetime specifier [E0106]">&</error>str,
            b: (bool, (u8, <error>&</error>f64)),
            c: Result<Box<<error>&</error>u32>, u8>,
            f: <error>&</error>Fn (&u32) -> &u32,
        }
    """)

    fun `test E0106 missing lifetime in tuple struct field`() = checkErrors("""
        struct Foo<'a> (
            &'a str,
            (bool, (u8, &'a f64)),
            &'a Fn (&u32) -> &u32);
        struct Bar<'a> (
            &'a str,
            <error descr="Missing lifetime specifier [E0106]">&</error>str,
            (bool, (u8, <error>&</error>f64)),
            <error>&</error>Fn (&u32) -> &u32);
    """)

    fun `test E0106 missing lifetime in enum`() = checkErrors("""
        enum Foo<'a> {
            A(&'a str),
            B(bool, (u8, &'a f64)),
            F(&'a Fn (&u32) -> &u32),
        }
        enum Bar<'a> {
            O(&'a str),
            A(<error descr="Missing lifetime specifier [E0106]">&</error>str),
            B(bool, (u8, <error>&</error>f64)),
            F(<error>&</error>Fn (&u32) -> &u32),
        }
    """)

    fun `test E0106 missing lifetime in type alias`() = checkErrors("""
        type Str = &'static str;
        type Foo<'a> = &'a Fn (&u32) -> &u32;

        type U32 = <error descr="Missing lifetime specifier [E0106]">&</error>u32;
        type Tuple = (bool, (u8, <error>&</error>f64));
        type Func = <error>&</error>Fn (&u32) -> &u32;
    """)

    fun `test E0106 missing lifetime ignores raw pointers`() = checkErrors("""
        struct Foo {
            raw: *const i32   // Must not be highlighted
        }
    """)

    fun `test E0106 missing lifetime in base types`() = checkErrors("""
        struct Foo1<'a>(&'a str);
        struct Foo2<'a, 'b> { a: &'a u32, b: &'b str }

        type Err1 = <error descr="Missing lifetime specifier [E0106]">Foo1</error>;
        struct Err2<'a> { a: <error>Foo2<></error>, o: &'a u32 }
        enum Err3<'d> { A(&'d Box<<error>Foo1</error>> ) }
    """)

    fun `test E0107 wrong number of lifetime parameters`() = checkErrors("""
        struct Foo0;
        struct Foo1<'a>(&'a str);
        struct Foo2<'a, 'b> { a: &'a u32, b: &'b str }

        struct Ok<'a> { a: Foo0, b: Foo1<'a>, c: Foo2<'a, 'a> }

        struct Err<'a, 'b, 'c> {
            a: <error descr="Wrong number of lifetime parameters: expected 0, found 2 [E0107]">Foo0<'a, 'b></error>,
            b: <error descr="Wrong number of lifetime parameters: expected 1, found 3 [E0107]">Foo1<'a, 'b, 'c></error>,
            c: <error descr="Wrong number of lifetime parameters: expected 2, found 1 [E0107]">Foo2<'a></error>,
        }
        type TErr<'a> = <error>Foo2<'a></error>;
        enum EErr<'a> { E(Box<<error>Foo1<'a, 'a></error>>) }
    """)

    fun `test E0121 type placeholder in signatures`() = checkErrors("""
        fn ok(_: &'static str) {
            let four = |x: _| 4;
            let _ = match (8, 3) { (_, _) => four(1) };
            if let Some(_) = Some(0) {}
        }

        fn foo(a: <error descr="The type placeholder `_` is not allowed within types on item signatures [E0121]">_</error>) {}
        fn bar() -> <error>_</error> {}
        fn baz(t: (u32, <error>_</error>)) -> (bool, (f64, <error>_</error>)) {}
        static FOO: <error>_</error> = 42;
    """)

    fun testE0124_NameDuplicationInStruct() = checkErrors("""
        struct S {
            no_dup: bool,
            <error descr="Field `dup` is already declared [E0124]">dup</error>: f64,
            <error descr="Field `dup` is already declared [E0124]">dup</error>: f64
        }

        enum E {
            VAR1 {
                no_dup: bool
            },
            VAR2 {
                no_dup: bool,
                <error descr="Field `dup` is already declared [E0124]">dup</error>: f64,
                <error descr="Field `dup` is already declared [E0124]">dup</error>: f64
            }
        }
    """)

    fun testE0185_SelfInImplNotInTrait() = checkErrors("""
        trait T {
            fn ok_foo(&self, x: u32);
            fn ok_bar(&mut self);
            fn ok_baz(self);
            fn foo(x: u32);
            fn bar();
            fn baz(o: bool);
        }
        struct S;
        impl T for S {
            fn ok_foo(&self, x: u32) {}
            fn ok_bar(&mut self) {}
            fn ok_baz(self) {}
            fn foo(<error descr="Method `foo` has a `&self` declaration in the impl, but not in the trait [E0185]">&self</error>, x: u32) {}
            fn bar(<error descr="Method `bar` has a `&mut self` declaration in the impl, but not in the trait [E0185]">&mut self</error>) {}
            fn baz(<error descr="Method `baz` has a `self` declaration in the impl, but not in the trait [E0185]">self</error>, o: bool) {}
        }
    """)

    fun testE0186_SelfInTraitNotInImpl() = checkErrors("""
        trait T {
            fn ok_foo(&self, x: u32);
            fn ok_bar(&mut self);
            fn ok_baz(self);
            fn foo(&self, x: u32);
            fn bar(&mut self);
            fn baz(self, o: bool);
        }
        struct S;
        impl T for S {
            fn ok_foo(&self, x: u32) {}
            fn ok_bar(&mut self) {}
            fn ok_baz(self) {}
            fn foo<error descr="Method `foo` has a `&self` declaration in the trait, but not in the impl [E0186]">(x: u32)</error> {}
            fn bar<error descr="Method `bar` has a `&mut self` declaration in the trait, but not in the impl [E0186]">()</error> {}
            fn baz<error descr="Method `baz` has a `self` declaration in the trait, but not in the impl [E0186]">(o: bool)</error> {}
        }
    """)

    fun testE0201_NameDuplicationInImpl() = checkErrors("""
        struct Foo;
        impl Foo {
            fn fn_unique() {}
            fn <error descr="Duplicate definitions with name `dup` [E0201]">dup</error>(&self, a: u32) {}
            fn <error descr="Duplicate definitions with name `dup` [E0201]">dup</error>(&self, a: u32) {}
        }

        trait Bar {
            const UNIQUE: u32;
            const TRAIT_DUP: u32;
            fn unique() {}
            fn trait_dup() {}
        }
        impl Bar for Foo {
            const UNIQUE: u32 = 14;
            const <error descr="Duplicate definitions with name `TRAIT_DUP` [E0201]">TRAIT_DUP</error>: u32 = 101;
            const <error descr="Duplicate definitions with name `TRAIT_DUP` [E0201]">TRAIT_DUP</error>: u32 = 101;
            fn unique() {}
            fn <error descr="Duplicate definitions with name `trait_dup` [E0201]">trait_dup</error>() {}
            fn <error descr="Duplicate definitions with name `trait_dup` [E0201]">trait_dup</error>() {}
        }
    """)

    fun testE0202_TypeAliasInInherentImpl() = checkErrors("""
        struct Foo;
        impl Foo {
            <error descr="Associated types are not allowed in inherent impls [E0202]">type Long = i64;</error>
        }
    """)

    fun `test E0243 number of type arguments is less than expected`() = checkErrors("""
        struct Foo1<T> { t: T }
        struct Foo2<T, U> { t: T, u: U }
        struct Foo2to3<T, U, V = bool> { t: T, u: U, v: V }

        struct Ok {
            ok1: Foo1<u32>,
            ok2: Foo2<u32, bool>,
            ok3: Foo2to3<u8, u8>,
        }

        struct Err {
            err1: <error descr="Wrong number of type arguments: expected 1, found 0 [E0243]">Foo1</error>,
            err2: <error descr="Wrong number of type arguments: expected 2, found 1 [E0243]">Foo2<u32></error>,
            err3: <error descr="Wrong number of type arguments: expected at least 2, found 1 [E0243]">Foo2to3<u32></error>,
        }

        impl <error>Foo1</error> {}
        fn err(f: <error>Foo2<u32></error>) -> <error>Foo1</error> {}
        type Type = <error>Foo2to3<u8></error>;
    """)

    fun `test E0243 ignores Fn-traits`() = checkErrors("""
        fn foo(f: &mut FnOnce(u32) -> bool) {}  // No annotation despite the fact that FnOnce has a type parameter
    """)

    fun `test E0243 ignores Self type`() = checkErrors("""
        struct Foo<T> { t: T }
        impl<T> Foo<T> {
            fn foo(s: Self) {}
        }
    """)

    fun `test E0244 number of type arguments is greater than expected`() = checkErrors("""
        struct Foo0;
        struct Foo1<T> { t: T }
        struct Foo1to2<T, U = bool> { t: T, u: U }

        struct Ok {
            ok1: Foo0,
            ok2: Foo1<u32>,
            ok3: Foo1to2<u8>,
            ok4: Foo1to2<u8, bool>,
        }

        struct Err {
            err1: <error descr="Wrong number of type arguments: expected 0, found 2 [E0244]">Foo0<u32, bool></error>,
            err2: <error descr="Wrong number of type arguments: expected 1, found 2 [E0244]">Foo1<u8, f64></error>,
            err3: <error descr="Wrong number of type arguments: expected at most 2, found 3 [E0244]">Foo1to2<u32, f32, bool></error>,
        }

        impl <error>Foo0<u8></error> {}
        fn err(f: <error>Foo1<u32, bool></error>) -> <error>Foo1to2<u8, u8, u8></error> {}
        type Type = <error>Foo1<u8, bool, f64></error>;
    """)

    fun `test E0261 undeclared lifetimes`() = checkErrors("""
        fn foo<'a, 'b>(x: &'a u32, f: &'b Fn(&'b u8) -> &'b str) -> &'a u32 { x }
        const FOO: for<'a> fn(&'a u32) -> &'a u32 = foo_func;
        struct Struct<'a> { s: &'a str }
        enum En<'a, 'b> { A(&'a u32), B(&'b bool) }
        type Str<'d> = &'d str;

        fn foo_err<'a>(x: &<error descr="Use of undeclared lifetime name `'b` [E0261]">'b</error> str) {}
        fn bar() {
            'foo: loop {
                let _: &<error descr="Use of undeclared lifetime name `'foo` [E0261]">'foo</error> str;
            }
        }
    """)

    fun `test E0261 not applied to static lifetimes`() = checkErrors("""
        const ZERO: &'static u32 = &0;
        fn foo(a: &'static str) {}
    """)

    fun testE0263_LifetimeNameDuplicationInGenericParams() = checkErrors("""
        fn foo<'a, 'b>(x: &'a str, y: &'b str) { }
        struct Str<'a, 'b> { a: &'a u32, b: &'b f64 }
        impl<'a, 'b> Str<'a, 'b> {}
        enum Direction<'a, 'b> { LEFT(&'a str), RIGHT(&'b str) }
        trait Trait<'a, 'b> {}

        fn bar<<error descr="Lifetime name `'a` declared twice in the same scope [E0263]">'a</error>, 'b, <error>'a</error>>(x: &'a str, y: &'b str) { }
        struct St<<error>'a</error>, 'b, <error>'a</error>> { a: &'a u32, b: &'b f64 }
        impl<<error>'a</error>, 'b, <error>'a</error>> Str<'a, 'b> {}
        enum Dir<<error>'a</error>, 'b, <error>'a</error>> { LEFT(&'a str), RIGHT(&'b str) }
        trait Tr<<error>'a</error>, 'b, <error>'a</error>> {}
    """)

    fun testE0379_ConstTraitFunction() = checkErrors("""
        trait Foo {
            fn foo();
            <error descr="Trait functions cannot be declared const [E0379]">const</error> fn bar();
        }
    """)

    fun `test E0392 unused lifetime parameter`() = checkErrors("""
        struct SOk<'a> { a: &'a u32 }
        enum EOk<'a> { E(&'a u32) }

        struct SErr<'a, <error descr="Parameter `'b` is never used [E0392]">'b</error>> { a: &'a u32 }
        enum EErr<'a, <error>'b</error>> { E(&'a u32) }
    """)

    fun `test E0392 unused type parameter`() = checkErrors("""
        struct SOk<T> { t: T }
        enum EOk<T> { E(T) }

        struct SErr<T, <error descr="Parameter `U` is never used [E0392]">U</error>> { t: T }
        enum EErr<T, <error>U</error>> { E(T) }
    """)

    fun testE0403_NameDuplicationInGenericParams() = checkErrors("""
        fn sub<T, P>() {}
        struct Str<T, P> { t: T, p: P }
        impl<T, P> Str<T, P> {}
        enum Direction<T, P> { LEFT(T), RIGHT(P) }
        trait Trait<T, P> {}

        fn add<<error descr="The name `T` is already used for a type parameter in this type parameter list [E0403]">T</error>, <error>T</error>, P>() {}
        struct S< <error>T</error>, <error>T</error>, P> { t: T, p: P }
        impl<     <error>T</error>, <error>T</error>, P> S<T, T, P> {}
        enum En<  <error>T</error>, <error>T</error>, P> { LEFT(T), RIGHT(P) }
        trait Tr< <error>T</error>, <error>T</error>, P> { fn foo(t: T) -> P; }
    """)

    fun testE0407_UnknownMethodInTraitImpl() = checkErrors("""
        trait T {
            fn foo();
        }
        impl T for () {
            fn foo() {}
            fn <error descr="Method `quux` is not a member of trait `T` [E0407]">quux</error>() {}
        }
    """)

    fun testE0415_NameDuplicationInParamList() = checkErrors("""
        fn foo(x: u32, X: u32) {}
        fn bar<T>(T: T) {}

        fn simple(<error descr="Identifier `a` is bound more than once in this parameter list [E0415]">a</error>: u32,
                  b: bool,
                  <error>a</error>: f64) {}
        fn tuples(<error>a</error>: u8, (b, (<error>a</error>, c)): (u16, (u32, u64))) {}
    """)

    fun `test E0426 undeclared label`() = checkErrors("""
        fn ok() {
            'foo: loop { break 'foo }
            'bar: while true { continue 'bar }
            'baz: for _ in 0..3 { break 'baz }
            'outer: loop {
                'inner: while true { break 'outer }
            }
        }

        fn err<'a>(a: &'a str) {
            'foo: loop { continue <error descr="Use of undeclared label `'bar` [E0426]">'bar</error> }
            while true { break <error descr="Use of undeclared label `'static` [E0426]">'static</error> }
            for _ in 0..1 { break <error descr="Use of undeclared label `'a` [E0426]">'a</error> }
        }
    """)

    fun testE0428_NameDuplicationInCodeBlock() = checkErrors("""
        fn abc() {
            const UNIQUE_CONST: i32 = 10;
            static UNIQUE_STATIC: f64 = 0.72;
            fn unique_fn() {}
            struct UniqueStruct;
            trait UniqueTrait {}
            enum UniqueEnum {}
            mod unique_mod {}

            const  <error descr="A value named `Dup` has already been defined in this block [E0428]">Dup</error>: u32 = 20;
            static <error descr="A value named `Dup` has already been defined in this block [E0428]">Dup</error>: i64 = -1.3;
            fn     <error descr="A value named `Dup` has already been defined in this block [E0428]">Dup</error>() {}
            struct <error descr="A type named `Dup` has already been defined in this block [E0428]">Dup</error>;
            trait  <error descr="A type named `Dup` has already been defined in this block [E0428]">Dup</error> {}
            enum   <error descr="A type named `Dup` has already been defined in this block [E0428]">Dup</error> {}
            mod    <error descr="A type named `Dup` has already been defined in this block [E0428]">Dup</error> {}
        }
    """)

    fun testE0428_NameDuplicationInEnum() = checkErrors("""
        enum Directions {
            NORTH,
            <error descr="Enum variant `SOUTH` is already declared [E0428]">SOUTH</error> { distance: f64 },
            WEST,
            <error descr="Enum variant `SOUTH` is already declared [E0428]">SOUTH</error> { distance: f64 },
            EAST
        }
    """)

    fun testE0428_NameDuplicationInForeignMod() = checkErrors("""
        extern "C" {
            static mut UNIQUE: u16;
            fn unique();

            static mut <error descr="A value named `DUP` has already been defined in this module [E0428]">DUP</error>: u32;
            static mut <error descr="A value named `DUP` has already been defined in this module [E0428]">DUP</error>: u32;

            fn <error descr="A value named `dup` has already been defined in this module [E0428]">dup</error>();
            fn <error descr="A value named `dup` has already been defined in this module [E0428]">dup</error>();
        }
    """)

    fun testE0428_NameDuplicationInFile() = checkErrors("""
        const UNIQUE_CONST: i32 = 10;
        static UNIQUE_STATIC: f64 = 0.72;
        fn unique_fn() {}
        struct UniqueStruct;
        trait UniqueTrait {}
        enum UniqueEnum {}
        mod unique_mod {}

        const <error descr="A value named `Dup` has already been defined in this module [E0428]">Dup</error>: u32 = 20;
        static <error descr="A value named `Dup` has already been defined in this module [E0428]">Dup</error>: i64 = -1.3;
        fn     <error descr="A value named `Dup` has already been defined in this module [E0428]">Dup</error>() {}
        struct <error descr="A type named `Dup` has already been defined in this module [E0428]">Dup</error>;
        trait  <error descr="A type named `Dup` has already been defined in this module [E0428]">Dup</error> {}
        enum   <error descr="A type named `Dup` has already been defined in this module [E0428]">Dup</error> {}
        mod    <error descr="A type named `Dup` has already been defined in this module [E0428]">Dup</error> {}
    """)

    fun testE0428_NameDuplicationInModule() = checkErrors("""
        mod foo {
            const UNIQUE_CONST: i32 = 10;
            static UNIQUE_STATIC: f64 = 0.72;
            fn unique_fn() {}
            struct UniqueStruct;
            trait UniqueTrait {}
            enum UniqueEnum {}
            mod unique_mod {}

            const <error descr="A value named `Dup` has already been defined in this module [E0428]">Dup</error>: u32 = 20;
            static <error descr="A value named `Dup` has already been defined in this module [E0428]">Dup</error>: i64 = -1.3;
            fn     <error descr="A value named `Dup` has already been defined in this module [E0428]">Dup</error>() {}
            struct <error descr="A type named `Dup` has already been defined in this module [E0428]">Dup</error>;
            trait  <error descr="A type named `Dup` has already been defined in this module [E0428]">Dup</error> {}
            enum   <error descr="A type named `Dup` has already been defined in this module [E0428]">Dup</error> {}
            mod    <error descr="A type named `Dup` has already been defined in this module [E0428]">Dup</error> {}
        }
    """)

    fun testE0428_NameDuplicationInTrait() = checkErrors("""
        trait T {
            type NO_DUP_T;
            const NO_DUP_C: u8;
            fn no_dup_f();

            type <error descr="A type named `DUP_T` has already been defined in this trait [E0428]">DUP_T</error>;
            type <error descr="A type named `DUP_T` has already been defined in this trait [E0428]">DUP_T</error>;

            const <error descr="A value named `DUP_C` has already been defined in this trait [E0428]">DUP_C</error>: u32;
            const <error descr="A value named `DUP_C` has already been defined in this trait [E0428]">DUP_C</error>: u32;

            fn <error descr="A value named `dup` has already been defined in this trait [E0428]">dup</error>(&self);
            fn <error descr="A value named `dup` has already been defined in this trait [E0428]">dup</error>(&self);
        }
    """)

    fun testE0428_RespectsNamespaces() = checkErrors("""
        mod m {
            // Consts and types are in different namespaces
            type  NO_C_DUP = bool;
            const NO_C_DUP: u32 = 10;

            // Functions and types are in different namespaces
            type NO_F_DUP = u8;
            fn   NO_F_DUP() {}

            // Consts and functions are in the same namespace (values)
            fn <error descr="A value named `DUP_V` has already been defined in this module [E0428]">DUP_V</error>() {}
            const <error>DUP_V</error>: u8 = 1;

            // Enums and traits are in the same namespace (types)
            trait <error descr="A type named `DUP_T` has already been defined in this module [E0428]">DUP_T</error> {}
            enum <error>DUP_T</error> {}

            <error descr="Unresolved module">mod foo;</error>
            fn foo() {}
        }
    """)

    fun testE0428_RespectsCrateAliases() = checkErrors("""
        extern crate num as num_lib;
        mod num {}

        // FIXME: ideally we want to highlight these
        extern crate foo;
        mod foo {}
    """)

    fun testE0428_IgnoresLocalBindings() = checkErrors("""
        mod no_dup {
            fn no_dup() {
                let no_dup: bool = false;
                fn no_dup(no_dup: u23) {
                    mod no_dup {}
                }
            }
        }
    """)

    fun testE0428_IgnoresInnerContainers() = checkErrors("""
        mod foo {
            const NO_DUP: u8 = 4;
            fn f() {
                const NO_DUP: u8 = 7;
                { const NO_DUP: u8 = 9; }
            }
            struct S { NO_DUP: u8 }
            trait T { const NO_DUP: u8 = 3; }
            enum E { NO_DUP }
            mod m { const NO_DUP: u8 = 1; }
        }
    """)

    fun testE0428_RespectsCfgAttribute() = checkErrors("""
        mod opt {
            #[cfg(not(windows))] mod foo {}
            #[cfg(windows)]     mod foo {}

            #[cfg(windows)] fn <error descr="A value named `hello_world` has already been defined in this module [E0428]">hello_world</error>() {}
            fn <error descr="A value named `hello_world` has already been defined in this module [E0428]">hello_world</error>() {}
        }
    """)

    fun testE0449_UnnecessaryPub() = checkErrors("""
        <error descr="Unnecessary visibility qualifier [E0449]">pub</error> extern "C" { }

        pub struct S {
            foo: bool,
            pub bar: u8,
            pub baz: (u32, f64)
        }
        <error>pub</error> impl S {}

        struct STuple (pub u32, f64);

        pub enum E {
            FOO {
                bar: u32,
                <error>pub</error> baz: u32
            },
            BAR(<error>pub</error> u32, f64)
        }

        pub trait Foo {
            type A;
            fn b();
            const C: u32;
        }
        struct Bar;
        <error>pub</error> impl Foo for Bar {
            <error>pub</error> type A = u32;
            <error>pub</error> fn b() {}
            <error>pub</error> const C: u32 = 10;
        }
    """)

    fun `testE0424 self in impl`() = checkErrors("""
        struct Foo;

        impl Foo {
            fn foo() {
                let a = <error descr="The self keyword was used in a static method [E0424]">self</error>;
            }
        }
    """)

    fun `test self expression outside function`() = checkErrors("""
        const C: () = <error descr="self value is not available in this context">self</error>;
    """)

    fun `testE0424 ignore non static`() = checkErrors("""
        struct Foo;

        impl Foo {
            fn foo(self) {
                let a = self;
            }
        }
    """)

    fun `testE0424 ignore module path`() = checkErrors("""
        fn foo() {
        }

        fn bar() {
            self::foo()
        }
    """)
}
