import { defineStore } from 'pinia'
import { ref } from 'vue'
import { getProfile, saveProfile as saveProfileApi } from '@/api/student'

/** 求职画像状态管理 */
export const useProfileStore = defineStore('profile', () => {
  const profile = ref<any>(null)

  async function fetchProfile() {
    const res: any = await getProfile()
    profile.value = res.data
  }

  async function saveProfile(data: any) {
    const res: any = await saveProfileApi(data)
    profile.value = res.data
    return res // 返回完整响应供调用方使用
  }

  return { profile, fetchProfile, saveProfile }
})
