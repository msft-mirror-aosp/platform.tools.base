
#include <gtest/gtest.h>

#include <vector>

#include "slicer/reader.h"
#include "tools/base/deploy/installer/apk_archive.h"
#include "tools/base/deploy/installer/dex_view.h"

using namespace deploy;

namespace {

class DexViewTest : public testing::TestWithParam<const char*> {};

constexpr const char* UNCOMPRESSED_DEX_APK_PATH =
    "tools/base/deploy/installer/tests/data/uncompressedDex.apk";
constexpr const char* CONTAINER_DEX_APK_PATH =
    "tools/base/deploy/installer/tests/data/containerDex.apk";

std::vector<std::string_view> GetAllDeclaredClasses(uint8_t* data) {
  std::vector<std::string_view> result;
  for (const auto& reader : DexContainerReader(data)) {
    auto type_ids = reader.TypeIds();
    for (const dex::ClassDef& def : reader.ClassDefs()) {
      dex::u4 class_idx = def.class_idx;
      dex::u4 descriptor_idx = type_ids[class_idx].descriptor_idx;
      std::string_view signature = reader.GetStringMUTF8(descriptor_idx);
      result.push_back(signature);
    }
  }
  return result;
}

}  // namespace

TEST(DexContainerTest, NoMissingDeclarations) {
  ApkArchive archive(CONTAINER_DEX_APK_PATH);
  std::vector<ApkArchive::Entry> entries = GetUncompressedDexEntries(archive);
  ASSERT_EQ(1, entries.size());
  std::vector<std::string_view> decls =
      GetAllDeclaredClasses(entries.front().payload);

  // Check that contains declarations for `Bar` and `Foo` classes
  ASSERT_NE(std::find(decls.begin(), decls.end(), "LBar;"), decls.end());
  ASSERT_NE(std::find(decls.begin(), decls.end(), "LFoo;"), decls.end());
}

TEST_P(DexViewTest, ContainsAllClasses) {
  ApkArchive archive(GetParam());

  struct DexFileAndDeclarations {
    DexView view;
    std::vector<std::string_view> decls;
  };

  std::vector<DexFileAndDeclarations> all_dex_files;
  for (const auto& entry : GetUncompressedDexEntries(archive)) {
    DexFileAndDeclarations dex_file{DexView(entry.payload),
                                    GetAllDeclaredClasses(entry.payload)};

    for (const auto& sig : dex_file.decls) {
      ASSERT_TRUE(dex_file.view.ContainsClassDeclaration(sig));
    }
    all_dex_files.push_back(dex_file);
  }

  // A DEX file shouldn't declare classes that are declared
  // in other DEX files.
  for (size_t i = 0; i < all_dex_files.size(); i++) {
    const DexFileAndDeclarations& dex_file_i = all_dex_files[i];
    for (size_t j = 0; j < all_dex_files.size(); j++) {
      if (i == j) {
        continue;
      }
      const DexFileAndDeclarations& dex_file_j = all_dex_files[j];
      for (const auto& sig : dex_file_j.decls) {
        ASSERT_FALSE(dex_file_i.view.ContainsClassDeclaration(sig));
      }
    }
  }
}

INSTANTIATE_TEST_SUITE_P(DexViewTest, DexViewTest,
                         testing::Values(UNCOMPRESSED_DEX_APK_PATH,
                                         CONTAINER_DEX_APK_PATH));
