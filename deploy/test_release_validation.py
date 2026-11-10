import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('agent', Path(__file__).with_name('deploy-agent.py'))
agent = importlib.util.module_from_spec(spec)
spec.loader.exec_module(agent)


class ReleaseBoundaryTest(unittest.TestCase):
    def test_accepts_only_our_registry_and_a_full_digest(self):
        release = {'image': agent.IMAGE_PREFIX + '@sha256:' + 'a' * 64, 'sha': 'b' * 40}
        self.assertEqual(agent.validate(release), release)

    def test_rejects_untrusted_registry_tags_and_shell_payloads(self):
        for image in ['docker.io/attacker/backend:latest', agent.IMAGE_PREFIX + ':latest',
                      agent.IMAGE_PREFIX + '@sha256:' + 'a' * 64 + '; id']:
            with self.subTest(image=image), self.assertRaises(ValueError):
                agent.validate({'image': image, 'sha': 'b' * 40})

    def test_rejects_missing_or_invalid_commit_identity(self):
        for sha in ['', 'main', 'a' * 39, 'b' * 40 + '\n']:
            with self.subTest(sha=sha), self.assertRaises(ValueError):
                agent.validate({'image': agent.IMAGE_PREFIX + '@sha256:' + 'a' * 64, 'sha': sha})


if __name__ == '__main__':
    unittest.main()
