import { createRouter, createWebHistory } from 'vue-router'
import HomeView from '@/pages/HomePage.vue'
import { healthCheck } from '@/api/healthController.ts'
healthCheck().then((res) => {
  console.log(res)
})

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/',
      name: 'home',
      component: HomeView,
    },
  ],

})

export default router
