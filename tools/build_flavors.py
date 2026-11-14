"""构建与签名共用版本选择；Standard 优先交付不隐含 Low 已验证。"""

CHOICES = ('both', 'standard', 'low')


def selected_flavors(selection):
    if selection not in CHOICES:
        raise ValueError('UNKNOWN_BUILD_FLAVOR')
    variants = (('standard', 30), ('low', 23))
    return [item for item in variants if selection == 'both' or item[0] == selection]


def verification_tasks(selection, assemble):
    variants = [name.title() for name, _ in selected_flavors(selection)]
    tasks = [f':app:test{name}DebugUnitTest' for name in variants]
    tasks += [f':app:lint{name}Release' for name in variants]
    if assemble:
        tasks += [f':app:assemble{name}Release' for name in variants]
    return tasks
